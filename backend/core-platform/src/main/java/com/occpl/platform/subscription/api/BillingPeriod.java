package com.occpl.platform.subscription.api;

public enum BillingPeriod {
    MONTHLY(1), QUARTERLY(3), HALF_YEARLY(6), ANNUAL(12);

    private final int months;

    BillingPeriod(int months) {
        this.months = months;
    }

    public int months() {
        return months;
    }
}
