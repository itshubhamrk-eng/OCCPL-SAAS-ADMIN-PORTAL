package com.occpl.platform.provisioning.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Provisioning runner settings. Product-specific values (mode, path, timeout, max attempts) come from
 * the product's published configuration; these are the defaults and platform-wide settings.
 *
 * @param deprovisionDelay how long a company's data stays in a product after its entitlement ends
 *        (D-5 data retention) before the platform asks the product to remove the tenant
 * @param callbackTimeout how long an ASYNC product may take to report completion
 * @param callbackBaseUrl Core's base URL as reachable by product services, sent with each request so
 *        the product knows where to report completion
 * @param alertRecipients email addresses notified when a job fails permanently
 * @param tokenUri OAuth2 token endpoint for Core's own service client ({@code occpl-core-services})
 */
@ConfigurationProperties("occpl.provisioning")
record ProvisioningProperties(
        @DefaultValue("90d") Duration deprovisionDelay,
        @DefaultValue("10s") Duration defaultTimeout,
        @DefaultValue("5") int defaultMaxAttempts,
        @DefaultValue("30s") Duration initialBackoff,
        @DefaultValue("1h") Duration maxBackoff,
        @DefaultValue("15m") Duration callbackTimeout,
        @DefaultValue("20") int batchSize,
        String callbackBaseUrl,
        List<String> alertRecipients,
        String tokenUri,
        @DefaultValue("occpl-core-services") String clientId,
        String clientSecret) {

    ProvisioningProperties {
        alertRecipients = alertRecipients == null ? List.of()
                : alertRecipients.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** Exponential back-off after the given (1-based) failed attempt, capped at {@link #maxBackoff}. */
    Duration backoff(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        Duration delay = initialBackoff.multipliedBy(factor);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
