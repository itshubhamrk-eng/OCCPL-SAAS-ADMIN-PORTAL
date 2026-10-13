package com.occpl.platform.subscription.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What a company's live subscriptions grant; consumed by the entitlement module. */
public interface SubscriptionGrants {

    List<Grant> liveGrants(UUID companyId);

    record Grant(UUID subscriptionId, SubscriptionStatus status, Instant currentPeriodStart, Instant currentPeriodEnd,
            List<ProductGrant> products) {
    }

    record ProductGrant(UUID productId, List<FeatureGrant> features) {
    }

    /** {@code limit} null means unlimited (LIMIT features). */
    record FeatureGrant(UUID featureId, boolean enabled, Long limit) {
    }
}
