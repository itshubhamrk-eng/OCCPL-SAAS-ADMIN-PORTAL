package com.occpl.platform.subscription.internal;

import com.occpl.platform.subscription.api.SubscriptionBilling;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaSubscriptionBilling implements SubscriptionBilling {

    private final CompanySubscriptionRepository subscriptions;
    private final SubscriptionService service;
    private final PlanService plans;

    JpaSubscriptionBilling(CompanySubscriptionRepository subscriptions, SubscriptionService service, PlanService plans) {
        this.subscriptions = subscriptions;
        this.service = service;
        this.plans = plans;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NextPeriod> nextPeriod(UUID subscriptionId) {
        return subscriptions.findById(subscriptionId).map(s -> {
            SubscriptionPlan plan = plans.require(s.pendingPlanId() != null ? s.pendingPlanId() : s.planId());
            long price = s.pendingPlanId() != null ? plan.prices().getOrDefault(s.currency(), s.priceMinor())
                    : s.priceMinor();
            Instant start = s.currentPeriodEnd();
            return new NextPeriod(s.id(), s.companyId(), s.status(), plan.name(), s.currency(), price,
                    plan.billingPeriod(), start, CompanySubscription.addPeriod(start, plan.billingPeriod()),
                    s.cancelAtPeriodEnd());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> dueForInvoice(Instant until, int limit) {
        return subscriptions.dueForInvoice(until, Limit.of(limit));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentResult applyPayment(UUID subscriptionId, Instant periodStart) {
        CompanySubscription s = subscriptions.lockById(subscriptionId).orElse(null);
        if (s == null) {
            return PaymentResult.NOT_APPLICABLE;
        }
        return switch (s.status()) {
            case TRIAL -> {
                service.activateByPayment(s.companyId(), s.id(), periodStart);
                yield PaymentResult.APPLIED;
            }
            case ACTIVE, PAST_DUE -> {
                if (!s.currentPeriodEnd().equals(periodStart)) {
                    yield PaymentResult.ALREADY_COVERED; // renewed already (by hand or an earlier payment)
                }
                service.renew(s.companyId(), s.id());
                yield PaymentResult.APPLIED;
            }
            case SUSPENDED -> {
                service.renew(s.companyId(), s.id());
                yield PaymentResult.APPLIED;
            }
            case CANCELLED, EXPIRED -> PaymentResult.NOT_APPLICABLE;
        };
    }
}
