package com.occpl.platform.entitlement.api;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * Server-side entitlement decisions for product services (exposed to them in Phase 7) and for
 * other modules. Company status is always applied: a suspended or deactivated company has no access.
 */
public interface Entitlements {

    /** Access to a product, optionally to one feature of it. Never throws for "no access". */
    AccessDecision check(UUID companyId, String productCode, String featureCode);

    /**
     * Atomically adds {@code quantity} to a LIMIT feature's usage if it stays within the limit.
     * Repeating a call with the same {@code idempotencyKey} returns the original result without
     * counting again. Throws LIMIT_EXCEEDED (409) or ENTITLEMENT_REQUIRED (403).
     */
    UsageResult consume(UUID companyId, String productCode, String featureCode, long quantity, String idempotencyKey);

    /** Gives back stock usage (e.g. records deleted); never goes below zero. Idempotent like consume. */
    UsageResult release(UUID companyId, String productCode, String featureCode, long quantity, String idempotencyKey);

    /**
     * Maximum company members (ACTIVE + INVITED) allowed by SEATS features of accessible products;
     * empty means no seat limit applies.
     */
    OptionalLong memberLimit(UUID companyId);

    enum Reason { OK, COMPANY_INACTIVE, NO_ENTITLEMENT, SUSPENDED, FEATURE_NOT_INCLUDED, LIMIT_REACHED }

    record AccessDecision(boolean allowed, Reason reason, EntitlementStatus status, Long limit, Long used) {

        public Long remaining() {
            return limit == null || used == null ? null : Math.max(0, limit - used);
        }
    }

    record UsageResult(long used, Long limit, boolean replayed) {

        public Long remaining() {
            return limit == null ? null : Math.max(0, limit - used);
        }
    }
}
