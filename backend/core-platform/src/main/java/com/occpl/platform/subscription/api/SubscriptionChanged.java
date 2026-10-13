package com.occpl.platform.subscription.api;

import java.util.UUID;

/** Published in the transaction that changed a company's subscriptions; entitlements are recomputed from it. */
public record SubscriptionChanged(UUID companyId, UUID subscriptionId) {
}
