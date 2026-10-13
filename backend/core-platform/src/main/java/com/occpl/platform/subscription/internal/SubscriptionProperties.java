package com.occpl.platform.subscription.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Lifecycle timing (business decision D-5, 2026-10-13).
 *
 * @param gracePeriod how long after the period end an unpaid (PAST_DUE) subscription keeps access before SUSPENDED
 * @param suspendedExpiry how long a SUSPENDED subscription waits before it EXPIRES
 * @param dataRetention how long company data is kept after a subscription is CANCELLED or EXPIRED
 */
@ConfigurationProperties("occpl.subscriptions")
record SubscriptionProperties(@DefaultValue("7d") Duration gracePeriod, @DefaultValue("90d") Duration suspendedExpiry,
        @DefaultValue("90d") Duration dataRetention) {
}
