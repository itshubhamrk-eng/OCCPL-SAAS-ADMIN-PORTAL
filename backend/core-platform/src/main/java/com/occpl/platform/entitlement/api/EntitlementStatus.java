package com.occpl.platform.entitlement.api;

/** Product access derived from subscriptions: ACTIVE (trial/paid), GRACE (payment overdue, still allowed), SUSPENDED (denied). */
public enum EntitlementStatus {
    ACTIVE, GRACE, SUSPENDED;

    public boolean allowsAccess() {
        return this != SUSPENDED;
    }
}
