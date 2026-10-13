package com.occpl.platform.subscription.api;

/** Subscription lifecycle (DATABASE.md, subscription state machine). CANCELLED and EXPIRED are terminal. */
public enum SubscriptionStatus {
    TRIAL, ACTIVE, PAST_DUE, SUSPENDED, CANCELLED, EXPIRED;

    public boolean isLive() {
        return this != CANCELLED && this != EXPIRED;
    }
}
