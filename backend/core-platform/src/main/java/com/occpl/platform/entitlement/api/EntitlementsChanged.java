package com.occpl.platform.entitlement.api;

import java.util.Set;
import java.util.UUID;

/**
 * Published in the transaction of a subscription change when the set of products a company is
 * entitled to (in any status, including SUSPENDED) changes. Listeners run in the same transaction.
 */
public record EntitlementsChanged(UUID companyId, Set<UUID> addedProductIds, Set<UUID> removedProductIds) {

    public EntitlementsChanged {
        addedProductIds = Set.copyOf(addedProductIds);
        removedProductIds = Set.copyOf(removedProductIds);
    }
}
