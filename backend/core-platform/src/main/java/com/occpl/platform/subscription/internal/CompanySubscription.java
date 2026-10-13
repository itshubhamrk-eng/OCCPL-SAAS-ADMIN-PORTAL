package com.occpl.platform.subscription.internal;

import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.subscription.api.BillingPeriod;
import com.occpl.platform.subscription.api.SubscriptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "company_subscription")
class CompanySubscription {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false)
    private BillingPeriod billingPeriod;

    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    @Column(name = "trial_end")
    private Instant trialEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    @Column(name = "pending_plan_id")
    private UUID pendingPlanId;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "data_retention_until")
    private Instant dataRetentionUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CompanySubscription() {
    }

    /** New subscription: a trial of {@code trialDays}, or an ACTIVE first billing period. */
    CompanySubscription(UUID id, UUID companyId, SubscriptionPlan plan, String currency, int trialDays, Instant now) {
        this.id = id;
        this.companyId = companyId;
        this.planId = plan.id();
        this.currency = currency;
        this.priceMinor = plan.prices().get(currency);
        this.billingPeriod = plan.billingPeriod();
        this.currentPeriodStart = now;
        if (trialDays > 0) {
            this.status = SubscriptionStatus.TRIAL;
            this.trialEnd = now.plus(Duration.ofDays(trialDays));
            this.currentPeriodEnd = trialEnd;
        } else {
            this.status = SubscriptionStatus.ACTIVE;
            this.currentPeriodEnd = addPeriod(now, billingPeriod);
        }
        this.statusChangedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static Instant addPeriod(Instant start, BillingPeriod period) {
        return start.atOffset(ZoneOffset.UTC).plusMonths(period.months()).toInstant();
    }

    SubscriptionStatus apply(SubscriptionStateMachine.Event event, Instant now) {
        SubscriptionStatus previous = status;
        status = SubscriptionStateMachine.next(status, event);
        statusChangedAt = now;
        updatedAt = now;
        return previous;
    }

    /** Trial converted: the first paid period starts now. */
    void startPaidPeriod(Instant now) {
        currentPeriodStart = now;
        currentPeriodEnd = addPeriod(now, billingPeriod);
        trialEnd = null;
    }

    /**
     * Next period after a renewal. A lapsed ACTIVE/PAST_DUE subscription continues from the old period
     * end (the customer pays for the period they used); a suspended one restarts now.
     */
    void advancePeriod(SubscriptionStatus previous, SubscriptionPlan pendingPlan, Instant now) {
        Instant start = previous == SubscriptionStatus.SUSPENDED ? now : currentPeriodEnd;
        if (pendingPlan != null) {
            planId = pendingPlan.id();
            priceMinor = pendingPlan.prices().get(currency);
            billingPeriod = pendingPlan.billingPeriod();
            pendingPlanId = null;
        }
        currentPeriodStart = start;
        currentPeriodEnd = addPeriod(start, billingPeriod);
        cancelAtPeriodEnd = false;
    }

    void switchPlanNow(SubscriptionPlan plan, Instant now) {
        planId = plan.id();
        priceMinor = plan.prices().get(currency);
        billingPeriod = plan.billingPeriod();
        pendingPlanId = null;
        updatedAt = now;
    }

    void scheduleDowngrade(UUID planId, Instant now) {
        pendingPlanId = planId;
        updatedAt = now;
    }

    void scheduleCancellation(boolean cancel, Instant now) {
        if (cancel && status != SubscriptionStatus.ACTIVE && status != SubscriptionStatus.TRIAL) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only active or trial subscriptions can be cancelled at period end.");
        }
        cancelAtPeriodEnd = cancel;
        updatedAt = now;
    }

    void end(Instant now, Duration retention) {
        endedAt = now;
        dataRetentionUntil = now.plus(retention);
        if (status == SubscriptionStatus.CANCELLED) {
            cancelledAt = now;
        }
        pendingPlanId = null;
        cancelAtPeriodEnd = false;
    }

    UUID id() { return id; }
    UUID companyId() { return companyId; }
    UUID planId() { return planId; }
    SubscriptionStatus status() { return status; }
    String currency() { return currency; }
    long priceMinor() { return priceMinor; }
    BillingPeriod billingPeriod() { return billingPeriod; }
    Instant currentPeriodStart() { return currentPeriodStart; }
    Instant currentPeriodEnd() { return currentPeriodEnd; }
    Instant trialEnd() { return trialEnd; }
    boolean cancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    UUID pendingPlanId() { return pendingPlanId; }
    Instant statusChangedAt() { return statusChangedAt; }
    Instant cancelledAt() { return cancelledAt; }
    Instant endedAt() { return endedAt; }
    Instant dataRetentionUntil() { return dataRetentionUntil; }
    Instant createdAt() { return createdAt; }
    long version() { return version; }
}
