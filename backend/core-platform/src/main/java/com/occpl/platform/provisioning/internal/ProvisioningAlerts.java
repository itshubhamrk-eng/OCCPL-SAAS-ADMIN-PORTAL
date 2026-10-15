package com.occpl.platform.provisioning.internal;

import com.occpl.platform.notification.api.EmailMessage;
import com.occpl.platform.notification.api.Notifications;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Raises a permanently failed job: error log, the {@code occpl.provisioning.jobs} counter
 * (outcome=failed, for Alertmanager) and an email to the configured operators. Called inside the
 * transaction that marks the job FAILED, so the email is sent only if that change commits.
 */
@Component
class ProvisioningAlerts {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningAlerts.class);

    private final Notifications notifications;
    private final ProvisioningProperties properties;
    private final MeterRegistry meters;

    ProvisioningAlerts(Notifications notifications, ProvisioningProperties properties, MeterRegistry meters) {
        this.notifications = notifications;
        this.properties = properties;
        this.meters = meters;
    }

    void jobFailed(ProvisioningJob job, String productCode, String companyCode) {
        meters.counter("occpl.provisioning.jobs", "outcome", "failed", "operation", job.operation().name(),
                "product", productCode).increment();
        log.error("Provisioning job {} ({} of company {} in product {}) failed after {} attempt(s): {}", job.id(),
                job.operation(), companyCode, productCode, job.attempts(), job.lastError());
        String subject = "[OCCPL] Provisioning failed: " + companyCode + " / " + productCode;
        String text = """
                A provisioning job failed and needs attention.

                Job:        %s
                Operation:  %s
                Company:    %s (%s)
                Product:    %s
                Attempts:   %d
                HTTP status: %s
                Error:      %s

                Retry or cancel it in the admin portal or with POST /api/v1/platform/provisioning-jobs/%s/retry.
                """.formatted(job.id(), job.operation(), companyCode, job.companyId(), productCode, job.attempts(),
                job.lastHttpStatus() == null ? "-" : job.lastHttpStatus(), job.lastError(), job.id());
        for (String recipient : properties.alertRecipients()) {
            notifications.enqueue(new EmailMessage("provisioning-failed", recipient, subject, text, null,
                    job.companyId()));
        }
    }

    void jobSucceeded(ProvisioningJob job, String productCode) {
        meters.counter("occpl.provisioning.jobs", "outcome", "succeeded", "operation", job.operation().name(),
                "product", productCode).increment();
    }
}
