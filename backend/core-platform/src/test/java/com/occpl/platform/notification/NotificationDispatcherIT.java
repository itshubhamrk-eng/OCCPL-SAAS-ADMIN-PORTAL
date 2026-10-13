package com.occpl.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.occpl.platform.notification.api.EmailMessage;
import com.occpl.platform.notification.api.Notifications;
import com.occpl.platform.notification.internal.NotificationDispatcher;
import com.occpl.platform.support.MySqlTestDatabase;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** Email outbox delivery against an in-process SMTP server. */
@ActiveProfiles("test")
@SpringBootTest(properties = {"spring.mail.host=127.0.0.1", "spring.mail.port=3025"})
class NotificationDispatcherIT {

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
    }

    @Autowired Notifications notifications;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;

    String recipient;

    @BeforeEach
    void setUp() {
        jdbc.update("update notification set status = 'SENT' where status = 'QUEUED'"); // isolate from other tests
        recipient = "user-" + UUID.randomUUID() + "@example.test";
    }

    @Test
    void sendsQueuedEmailOnceAndClearsTheBody() throws Exception {
        enqueue();
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(dispatcher.dispatchDue()).isZero();

        MimeMessage[] received = smtp.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo("Welcome");
        assertThat(GreenMailUtil.getBody(received[0])).contains("Hello text").contains("Hello <b>html</b>");
        Map<String, Object> row = row();
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("body_text")).isNull();
        assertThat(row.get("body_html")).isNull();
    }

    @Test
    void retriesWithBackoffAndGivesUpAfterMaxAttempts() {
        enqueue();
        smtp.stop();
        dispatcher.dispatchDue();
        Map<String, Object> afterFailure = row();
        assertThat(afterFailure.get("status")).isEqualTo("QUEUED");
        assertThat(afterFailure.get("attempts")).isEqualTo(1);
        assertThat(afterFailure.get("last_error")).isNotNull();
        assertThat(dispatcher.dispatchDue()).as("not due again until the back-off has passed").isZero();

        jdbc.update("update notification set attempts = 7, next_attempt_at = UTC_TIMESTAMP(6) where recipient_email = ?",
                recipient);
        dispatcher.dispatchDue();
        Map<String, Object> failed = row();
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("body_text")).isNull();
        smtp.start();
    }

    @Test
    void messageIsNotQueuedWhenTheBusinessTransactionRollsBack() {
        try {
            transactions.executeWithoutResult(status -> {
                notifications.enqueue(message());
                throw new IllegalStateException("business failure");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        assertThat(jdbc.queryForObject("select count(*) from notification where recipient_email = ?", Integer.class,
                recipient)).isZero();
    }

    private void enqueue() {
        transactions.executeWithoutResult(status -> notifications.enqueue(message()));
    }

    private EmailMessage message() {
        return new EmailMessage("test", recipient, "Welcome", "Hello text", "<p>Hello <b>html</b></p>", null);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("select status, attempts, last_error, body_text, body_html from notification "
                + "where recipient_email = ?", recipient);
    }
}
