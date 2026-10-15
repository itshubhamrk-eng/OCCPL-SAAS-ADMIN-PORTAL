package com.occpl.platform.subscription.internal;

import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanyStatus;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.id.UuidV7;
import com.occpl.platform.shared.paging.PageRequests;
import com.occpl.platform.shared.security.Actor;
import com.occpl.platform.subscription.api.BillingPeriod;
import com.occpl.platform.subscription.api.PlanStatus;
import com.occpl.platform.subscription.api.SubscriptionChanged;
import com.occpl.platform.subscription.api.SubscriptionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company subscriptions: assignment, plan changes, renewal, cancellation and the automatic lifecycle
 * (see {@link SubscriptionStateMachine}). Billing is separate: renewal is triggered by an
 * administrator today and by payment events once the gateway integration exists.
 */
@Service
public class SubscriptionService {

    private static final Map<String, String> SORTS = Map.of("createdAt", "createdAt", "currentPeriodEnd",
            "currentPeriodEnd", "status", "status");

    private final CompanySubscriptionRepository subscriptions;
    private final SubscriptionHistoryRepository history;
    private final PlanService plans;
    private final CompanyDirectory companies;
    private final AuditLogger audit;
    private final ApplicationEventPublisher events;
    private final SubscriptionProperties properties;
    private final Clock clock;

    SubscriptionService(CompanySubscriptionRepository subscriptions, SubscriptionHistoryRepository history,
            PlanService plans, CompanyDirectory companies, AuditLogger audit, ApplicationEventPublisher events,
            SubscriptionProperties properties, Clock clock) {
        this.subscriptions = subscriptions;
        this.history = history;
        this.plans = plans;
        this.companies = companies;
        this.audit = audit;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    public enum CancelMode { IMMEDIATE, PERIOD_END }

    public record SubscriptionView(UUID id, UUID companyId, UUID planId, String planCode, String planName,
            SubscriptionStatus status, String currency, long priceMinor, BillingPeriod billingPeriod,
            Instant currentPeriodStart, Instant currentPeriodEnd, Instant trialEnd, Instant graceUntil,
            boolean cancelAtPeriodEnd, UUID pendingPlanId, Instant cancelledAt, Instant endedAt,
            Instant dataRetentionUntil, Instant createdAt, List<HistoryView> history) {
    }

    public record HistoryView(String event, SubscriptionStatus fromStatus, SubscriptionStatus toStatus,
            UUID fromPlanId, UUID toPlanId, UUID actorUserId, String reason, Instant occurredAt) {
    }

    @Transactional
    public SubscriptionView assign(UUID companyId, UUID planId, String currency, boolean startTrial) {
        companies.find(companyId).filter(c -> c.status() != CompanyStatus.DEACTIVATED)
                .orElseThrow(() -> ApiException.notFound("Company"));
        SubscriptionPlan plan = plans.require(planId);
        if (plan.status() != PlanStatus.ACTIVE) {
            throw new ApiException(ErrorCode.CONFLICT, "Only active plans can be assigned.");
        }
        String code = currency.toUpperCase();
        if (!plan.prices().containsKey(code)) {
            throw ApiException.validation("The plan has no price in " + code + ".");
        }
        if (startTrial && plan.trialDays() == 0) {
            throw ApiException.validation("This plan has no trial period.");
        }
        Instant now = clock.instant();
        CompanySubscription subscription = subscriptions.saveAndFlush(new CompanySubscription(UuidV7.generate(clock),
                companyId, plan, code, startTrial ? plan.trialDays() : 0, now));
        record(subscription, "CREATED", null, null, plan.id(), null, now);
        return view(subscription, true);
    }

    @Transactional
    public SubscriptionView activate(UUID companyId, UUID subscriptionId) {
        CompanySubscription s = lock(companyId, subscriptionId);
        Instant now = clock.instant();
        SubscriptionStatus previous = s.apply(SubscriptionStateMachine.Event.ACTIVATE, now);
        s.startPaidPeriod(now);
        record(s, "ACTIVATED", previous, s.planId(), s.planId(), null, now);
        return view(s, true);
    }

    /** Trial converted by a payment for the period starting at {@code paidFrom} (billing). */
    @Transactional
    public SubscriptionView activateByPayment(UUID companyId, UUID subscriptionId, Instant paidFrom) {
        CompanySubscription s = lock(companyId, subscriptionId);
        Instant now = clock.instant();
        SubscriptionStatus previous = s.apply(SubscriptionStateMachine.Event.ACTIVATE, now);
        s.startPaidPeriodFrom(paidFrom, now);
        record(s, "ACTIVATED", previous, s.planId(), s.planId(), "Payment received", now);
        return view(s, true);
    }

    /** Starts the next paid period, applying a scheduled downgrade and clearing a scheduled cancellation. */
    @Transactional
    public SubscriptionView renew(UUID companyId, UUID subscriptionId) {
        CompanySubscription s = lock(companyId, subscriptionId);
        Instant now = clock.instant();
        UUID fromPlan = s.planId();
        SubscriptionPlan pending = s.pendingPlanId() == null ? null : plans.require(s.pendingPlanId());
        SubscriptionStatus previous = s.apply(SubscriptionStateMachine.Event.RENEW, now);
        s.advancePeriod(previous, pending, now);
        record(s, pending == null ? "RENEWED" : "RENEWED_WITH_DOWNGRADE", previous, fromPlan, s.planId(), null, now);
        return view(s, true);
    }

    /**
     * Upgrade (new price ≥ current price) takes effect immediately; a downgrade is scheduled for the
     * end of the current period so the customer keeps what they paid for.
     */
    @Transactional
    public SubscriptionView changePlan(UUID companyId, UUID subscriptionId, UUID newPlanId) {
        CompanySubscription s = lock(companyId, subscriptionId);
        if (s.status() != SubscriptionStatus.ACTIVE && s.status() != SubscriptionStatus.TRIAL) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only active or trial subscriptions can change plan.");
        }
        if (newPlanId.equals(s.planId())) {
            throw ApiException.validation("The subscription is already on this plan.");
        }
        SubscriptionPlan target = plans.require(newPlanId);
        if (target.status() != PlanStatus.ACTIVE) {
            throw new ApiException(ErrorCode.CONFLICT, "Only active plans can be assigned.");
        }
        Long targetPrice = target.prices().get(s.currency());
        if (targetPrice == null) {
            throw ApiException.validation("The new plan has no price in " + s.currency() + ".");
        }
        Instant now = clock.instant();
        UUID fromPlan = s.planId();
        if (targetPrice >= s.priceMinor()) {
            s.switchPlanNow(target, now);
            record(s, "UPGRADED", s.status(), fromPlan, target.id(), null, now);
        } else {
            s.scheduleDowngrade(target.id(), now);
            record(s, "DOWNGRADE_SCHEDULED", s.status(), fromPlan, target.id(), null, now);
        }
        return view(s, true);
    }

    @Transactional
    public SubscriptionView cancel(UUID companyId, UUID subscriptionId, CancelMode mode, String reason) {
        CompanySubscription s = lock(companyId, subscriptionId);
        Instant now = clock.instant();
        if (mode == CancelMode.PERIOD_END) {
            s.scheduleCancellation(true, now);
            record(s, "CANCELLATION_SCHEDULED", s.status(), s.planId(), s.planId(), reason, now);
        } else {
            SubscriptionStatus previous = s.apply(SubscriptionStateMachine.Event.CANCEL, now);
            s.end(now, properties.dataRetention());
            record(s, "CANCELLED", previous, s.planId(), s.planId(), reason, now);
        }
        return view(s, true);
    }

    @Transactional
    public SubscriptionView resume(UUID companyId, UUID subscriptionId) {
        CompanySubscription s = lock(companyId, subscriptionId);
        if (!s.cancelAtPeriodEnd()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "No cancellation is scheduled.");
        }
        Instant now = clock.instant();
        s.scheduleCancellation(false, now);
        record(s, "CANCELLATION_REVOKED", s.status(), s.planId(), s.planId(), null, now);
        return view(s, true);
    }

    @Transactional
    public SubscriptionView suspend(UUID companyId, UUID subscriptionId, String reason) {
        CompanySubscription s = lock(companyId, subscriptionId);
        Instant now = clock.instant();
        SubscriptionStatus previous = s.apply(SubscriptionStateMachine.Event.SUSPEND, now);
        record(s, "SUSPENDED", previous, s.planId(), s.planId(), reason, now);
        return view(s, true);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionView> forCompany(UUID companyId) {
        return subscriptions.findByCompanyIdOrderByCreatedAtDesc(companyId).stream().map(s -> view(s, false)).toList();
    }

    @Transactional(readOnly = true)
    public SubscriptionView get(UUID companyId, UUID subscriptionId) {
        return view(subscriptions.findByIdAndCompanyId(subscriptionId, companyId)
                .orElseThrow(() -> ApiException.notFound("Subscription")), true);
    }

    @Transactional(readOnly = true)
    public Page<SubscriptionView> search(SubscriptionStatus status, UUID companyId, UUID planId, Integer page,
            Integer size, List<String> sort) {
        return subscriptions.search(status, companyId, planId, PageRequests.of(page, size, sort, SORTS,
                Sort.by(Sort.Direction.DESC, "createdAt"))).map(s -> view(s, false));
    }

    // ------------------------------------------------------------------ automatic lifecycle (sweeper)

    /** Applies the time-based rule that is due for this subscription, if any. Idempotent. */
    @Transactional
    public boolean applyDueTransition(UUID subscriptionId) {
        CompanySubscription s = subscriptions.lockById(subscriptionId).orElse(null);
        if (s == null) {
            return false;
        }
        Instant now = clock.instant();
        switch (s.status()) {
            case TRIAL -> {
                if (s.trialEnd() != null && !s.trialEnd().isAfter(now)) {
                    expire(s, "TRIAL_EXPIRED", now);
                    return true;
                }
            }
            case ACTIVE -> {
                if (!s.currentPeriodEnd().isAfter(now)) {
                    if (s.cancelAtPeriodEnd()) {
                        expire(s, "EXPIRED_AT_PERIOD_END", now);
                    } else {
                        SubscriptionStatus previous = s.apply(SubscriptionStateMachine.Event.MARK_PAST_DUE, now);
                        record(s, "PAST_DUE", previous, s.planId(), s.planId(), "Period ended without renewal", now);
                    }
                    return true;
                }
            }
            case PAST_DUE -> {
                if (!s.currentPeriodEnd().plus(properties.gracePeriod()).isAfter(now)) {
                    SubscriptionStatus previous = s.apply(SubscriptionStateMachine.Event.SUSPEND, now);
                    record(s, "SUSPENDED", previous, s.planId(), s.planId(), "Grace period elapsed", now);
                    return true;
                }
            }
            case SUSPENDED -> {
                if (!s.statusChangedAt().plus(properties.suspendedExpiry()).isAfter(now)) {
                    expire(s, "EXPIRED_AFTER_SUSPENSION", now);
                    return true;
                }
            }
            default -> {
                return false;
            }
        }
        return false;
    }

    private void expire(CompanySubscription s, String event, Instant now) {
        SubscriptionStatus previous = s.apply(SubscriptionStateMachine.Event.EXPIRE, now);
        s.end(now, properties.dataRetention());
        record(s, event, previous, s.planId(), s.planId(), null, now);
    }

    // ------------------------------------------------------------------ helpers

    private CompanySubscription lock(UUID companyId, UUID subscriptionId) {
        CompanySubscription s = subscriptions.lockById(subscriptionId)
                .filter(found -> found.companyId().equals(companyId))
                .orElseThrow(() -> ApiException.notFound("Subscription"));
        return s;
    }

    /** History row, audit entry and the event that triggers entitlement recomputation. */
    private void record(CompanySubscription s, String event, SubscriptionStatus from, UUID fromPlan, UUID toPlan,
            String reason, Instant now) {
        subscriptions.flush();
        UUID actor = Actor.current().map(Actor::userId).orElse(null);
        history.save(new SubscriptionHistory(UuidV7.generate(clock), s.id(), event, from, s.status(), fromPlan, toPlan,
                actor, reason, now));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("event", event);
        details.put("status", s.status().name());
        details.put("planId", s.planId().toString());
        if (reason != null) {
            details.put("reason", reason);
        }
        AuditEvent auditEvent = AuditEvent.success("subscription." + event.toLowerCase(), "subscription", s.id(),
                s.companyId(), details);
        if (actor != null) {
            audit.record(auditEvent);
        } else {
            audit.recordSystem(auditEvent);
        }
        events.publishEvent(new SubscriptionChanged(s.companyId(), s.id()));
    }

    private SubscriptionView view(CompanySubscription s, boolean withHistory) {
        SubscriptionPlan plan = plans.require(s.planId());
        List<HistoryView> entries = withHistory ? history.findBySubscriptionIdOrderByOccurredAtAsc(s.id()).stream()
                .map(h -> new HistoryView(h.event(), h.fromStatus(), h.toStatus(), h.fromPlanId(), h.toPlanId(),
                        h.actorUserId(), h.reason(), h.occurredAt())).toList() : null;
        Instant graceUntil = s.status() == SubscriptionStatus.PAST_DUE
                ? s.currentPeriodEnd().plus(properties.gracePeriod()) : null;
        return new SubscriptionView(s.id(), s.companyId(), s.planId(), plan.code(), plan.name(), s.status(),
                s.currency(), s.priceMinor(), s.billingPeriod(), s.currentPeriodStart(), s.currentPeriodEnd(),
                s.trialEnd(), graceUntil, s.cancelAtPeriodEnd(), s.pendingPlanId(), s.cancelledAt(), s.endedAt(),
                s.dataRetentionUntil(), s.createdAt(), entries);
    }
}
