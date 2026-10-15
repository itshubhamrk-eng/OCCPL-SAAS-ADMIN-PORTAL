package com.occpl.platform.billing.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Razorpay credentials come from the environment only. Billing is disabled (API answers 503
 * BILLING_DISABLED, no invoices are created) until both API keys are set; webhooks are refused until
 * the webhook secret is set.
 *
 * @param invoiceLeadTime renewal invoices are created this long before the current period (or trial) ends
 * @param paymentWindow how long an issued invoice can be paid before the gateway expires it
 */
@ConfigurationProperties("occpl.billing")
record BillingProperties(
        @DefaultValue("https://api.razorpay.com") String razorpayApiUrl,
        String razorpayKeyId,
        String razorpayKeySecret,
        String razorpayWebhookSecret,
        @DefaultValue("7d") Duration invoiceLeadTime,
        @DefaultValue("14d") Duration paymentWindow,
        @DefaultValue("true") boolean emailInvoices,
        @DefaultValue("20") int batchSize) {

    boolean enabled() {
        return notBlank(razorpayKeyId) && notBlank(razorpayKeySecret);
    }

    boolean webhooksEnabled() {
        return notBlank(razorpayWebhookSecret);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
