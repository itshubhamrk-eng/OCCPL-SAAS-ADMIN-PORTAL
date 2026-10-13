package com.occpl.platform.notification.internal;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued email. Each run claims a batch with {@code FOR UPDATE SKIP LOCKED} and sends within
 * that transaction, so a crash leaves messages QUEUED for the next run (at-least-once delivery).
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRepository repository;
    private final JavaMailSender mailSender;
    private final NotificationProperties properties;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final Clock clock;

    NotificationDispatcher(NotificationRepository repository, JavaMailSender mailSender,
            NotificationProperties properties, TransactionTemplate transactions, MeterRegistry meters, Clock clock) {
        this.repository = repository;
        this.mailSender = mailSender;
        this.properties = properties;
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${occpl.notifications.dispatch-interval:10s}",
            initialDelayString = "${occpl.notifications.dispatch-interval:10s}")
    void scheduledDispatch() {
        dispatchDue();
    }

    /** Sends one batch of due messages; returns how many were processed. */
    public int dispatchDue() {
        Integer processed = transactions.execute(status -> {
            Instant now = clock.instant();
            var batch = repository.claimDue(now, properties.batchSize());
            for (Notification notification : batch) {
                send(notification, now);
            }
            return batch.size();
        });
        return processed == null ? 0 : processed;
    }

    private void send(Notification notification, Instant now) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, notification.bodyHtml() != null, "UTF-8");
            helper.setFrom(properties.from());
            helper.setTo(notification.recipientEmail());
            helper.setSubject(notification.subject());
            if (notification.bodyHtml() != null) {
                helper.setText(notification.bodyText(), notification.bodyHtml());
            } else {
                helper.setText(notification.bodyText());
            }
            mailSender.send(mime);
            notification.markSent(now);
            meters.counter("occpl.notifications.sent", "template", notification.template()).increment();
        } catch (Exception e) {
            boolean permanent = notification.markFailed(e.getMessage(), properties.maxAttempts(), now);
            meters.counter("occpl.notifications.failures", "template", notification.template()).increment();
            if (permanent) {
                log.error("Email {} ({}) permanently failed after {} attempts", notification.id(),
                        notification.template(), notification.attempts(), e);
            } else {
                log.warn("Email {} ({}) failed, will retry: {}", notification.id(), notification.template(),
                        e.toString());
            }
        }
    }
}
