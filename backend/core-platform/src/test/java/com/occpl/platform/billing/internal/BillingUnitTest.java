package com.occpl.platform.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.subscription.api.SubscriptionBilling;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class BillingUnitTest {

    private static final byte[] BODY = "{\"event\":\"invoice.paid\"}".getBytes(StandardCharsets.UTF_8);

    private static BillingProperties props(String key, String secret, String webhookSecret) {
        return new BillingProperties("https://api.razorpay.com", key, secret, webhookSecret, Duration.ofDays(7),
                Duration.ofDays(14), true, 20);
    }

    @Test
    void signatureIsHmacSha256OfTheExactBody() {
        String signature = WebhookSignature.hmacSha256(BODY, "s3cret");
        assertThat(signature).matches("[0-9a-f]{64}");
        assertThat(WebhookSignature.valid(BODY, signature, "s3cret")).isTrue();
        assertThat(WebhookSignature.valid(BODY, signature.toUpperCase(), "s3cret")).isTrue();
        assertThat(WebhookSignature.valid(BODY, signature, "other")).isFalse();
        assertThat(WebhookSignature.valid("{\"event\":\"invoice.paid\" }".getBytes(StandardCharsets.UTF_8), signature,
                "s3cret")).isFalse();
        assertThat(WebhookSignature.valid(BODY, null, "s3cret")).isFalse();
        assertThat(WebhookSignature.valid(BODY, signature, "")).isFalse();
    }

    @Test
    void billingNeedsBothKeysAndWebhooksNeedTheSecret() {
        assertThat(props("k", "s", null).enabled()).isTrue();
        assertThat(props("k", "", null).enabled()).isFalse();
        assertThat(props(null, "s", null).enabled()).isFalse();
        assertThat(props("k", "s", null).webhooksEnabled()).isFalse();
        assertThat(props(null, null, "w").webhooksEnabled()).isTrue();
    }

    @Test
    void unconfiguredBillingRefusesInsteadOfPretending() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        InvoiceService service = new InvoiceService(mock(InvoiceRepository.class), gateway,
                mock(SubscriptionBilling.class), mock(CompanyDirectory.class), mock(AuditLogger.class),
                props(null, null, null), mock(TransactionTemplate.class), Clock.systemUTC());
        assertThatThrownBy(() -> service.createForNextPeriod(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BILLING_DISABLED));
        assertThat(service.createDueInvoices()).isZero();
        verifyNoInteractions(gateway);

        PaymentEvents events = new PaymentEvents(mock(InvoiceRepository.class), mock(PaymentTransactionRepository.class),
                mock(SubscriptionBilling.class), service, mock(AuditLogger.class), mock(JdbcClient.class),
                JsonMapper.builder().build(), new SimpleMeterRegistry(), props(null, null, null),
                mock(TransactionTemplate.class), Clock.systemUTC());
        assertThatThrownBy(() -> events.receive(BODY, "x", "evt_1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BILLING_DISABLED));
    }
}
