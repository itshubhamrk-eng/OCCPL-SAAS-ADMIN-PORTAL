package com.occpl.platform.subscription.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the billing module needs from subscriptions: what the next period costs, which subscriptions
 * are due for an invoice, and applying a captured payment (S-8). Payments drive the same state
 * machine transitions an administrator can trigger by hand (activate, renew).
 */
public interface SubscriptionBilling {

    Optional<NextPeriod> nextPeriod(UUID subscriptionId);

    /**
     * Live subscriptions (TRIAL, ACTIVE, PAST_DUE) that are not cancelled at period end and whose
     * current period (or trial) ends at or before {@code until}.
     */
    List<UUID> dueForInvoice(Instant until, int limit);

    /**
     * Applies a payment for the period starting at {@code periodStart}: a trial is activated (keeping
     * its remaining days: paid until {@code periodStart} plus one period), an
     * active/past-due subscription is renewed if that period has not started yet on the
     * subscription, a suspended one is renewed. Idempotent: a period already covered is reported, not
     * applied twice. Runs in the caller's transaction.
     */
    PaymentResult applyPayment(UUID subscriptionId, Instant periodStart);

    enum PaymentResult { APPLIED, ALREADY_COVERED, NOT_APPLICABLE }

    /**
     * The period an invoice would cover now: it starts when the current period (or trial) ends; a
     * scheduled downgrade's plan and price apply to it.
     */
    record NextPeriod(UUID subscriptionId, UUID companyId, SubscriptionStatus status, String planName,
            String currency, long priceMinor, BillingPeriod billingPeriod, Instant periodStart, Instant periodEnd,
            boolean cancelAtPeriodEnd) {
    }
}
