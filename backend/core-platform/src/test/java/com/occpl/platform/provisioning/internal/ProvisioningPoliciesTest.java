package com.occpl.platform.provisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProvisioningPoliciesTest {

    private final ProvisioningProperties properties = new ProvisioningProperties(Duration.ofDays(90),
            Duration.ofSeconds(10), 5, Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMinutes(15), 20, null,
            List.of(" ops@occpl.test ", ""), null, "occpl-core-services", null);

    @Test
    void backoffDoublesFromThirtySecondsAndIsCappedAtOneHour() {
        assertThat(properties.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.backoff(5)).isEqualTo(Duration.ofMinutes(8));
        assertThat(properties.backoff(8)).isEqualTo(Duration.ofHours(1));
        assertThat(properties.backoff(60)).isEqualTo(Duration.ofHours(1));
        assertThat(properties.alertRecipients()).containsExactly("ops@occpl.test");
    }

    @Test
    void onlyTransientHttpOutcomesAreRetried() {
        assertThat(new ProductServiceClient.Result(null, "timeout").retryable()).isTrue();
        assertThat(new ProductServiceClient.Result(500, "x").retryable()).isTrue();
        assertThat(new ProductServiceClient.Result(503, "x").retryable()).isTrue();
        assertThat(new ProductServiceClient.Result(408, "x").retryable()).isTrue();
        assertThat(new ProductServiceClient.Result(429, "x").retryable()).isTrue();
        assertThat(new ProductServiceClient.Result(401, "x").retryable()).isTrue();
        assertThat(new ProductServiceClient.Result(400, "x").retryable()).isFalse();
        assertThat(new ProductServiceClient.Result(403, "x").retryable()).isFalse();
        assertThat(new ProductServiceClient.Result(409, "x").retryable()).isFalse();
        assertThat(new ProductServiceClient.Result(202, null).is2xx()).isTrue();
    }

    @Test
    void jobFailsPermanentlyAfterMaxAttemptsAndRetryStartsOver() {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");
        ProvisioningJob job = new ProvisioningJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ProvisioningJob.Operation.PROVISION, 5, now, now);
        job.claim(1, 2, Duration.ofSeconds(40), now);
        assertThat(job.failAttempt("HTTP 503", 503, true, Duration.ofSeconds(30), now)).isFalse();
        assertThat(job.status()).isEqualTo(ProvisioningJob.Status.PENDING);
        assertThat(job.nextAttemptAt()).isEqualTo(now.plusSeconds(30));
        job.claim(1, 2, Duration.ofSeconds(40), now);
        assertThat(job.failAttempt("HTTP 503", 503, true, Duration.ofSeconds(60), now)).isTrue();
        assertThat(job.status()).isEqualTo(ProvisioningJob.Status.FAILED);
        assertThat(job.completedAt()).isEqualTo(now);

        job.retry(now);
        assertThat(job.status()).isEqualTo(ProvisioningJob.Status.PENDING);
        assertThat(job.attempts()).isZero();
        assertThat(job.lastError()).isEqualTo("HTTP 503");
        job.claim(1, 2, Duration.ofSeconds(40), now);
        assertThat(job.failAttempt("bad request", 400, false, Duration.ofSeconds(30), now)).isTrue();
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.lastError()).isEqualTo("bad request");
    }

    @Test
    void longErrorsAreTruncated() {
        Instant now = Instant.now();
        ProvisioningJob job = new ProvisioningJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ProvisioningJob.Operation.DEPROVISION, 1, now, now);
        job.failPermanently("x".repeat(5000), now);
        assertThat(job.lastError()).hasSize(1000);
    }
}
