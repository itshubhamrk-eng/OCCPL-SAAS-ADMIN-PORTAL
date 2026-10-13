package com.occpl.platform.subscription.internal;

import com.occpl.platform.subscription.api.SubscriptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only history of a subscription. */
@Entity
@Table(name = "subscription_history")
class SubscriptionHistory {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(nullable = false, updatable = false)
    private String event;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private SubscriptionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private SubscriptionStatus toStatus;

    @Column(name = "from_plan_id", updatable = false)
    private UUID fromPlanId;

    @Column(name = "to_plan_id", updatable = false)
    private UUID toPlanId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected SubscriptionHistory() {
    }

    SubscriptionHistory(UUID id, UUID subscriptionId, String event, SubscriptionStatus fromStatus,
            SubscriptionStatus toStatus, UUID fromPlanId, UUID toPlanId, UUID actorUserId, String reason,
            Instant occurredAt) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.event = event;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.fromPlanId = fromPlanId;
        this.toPlanId = toPlanId;
        this.actorUserId = actorUserId;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    String event() { return event; }
    SubscriptionStatus fromStatus() { return fromStatus; }
    SubscriptionStatus toStatus() { return toStatus; }
    UUID fromPlanId() { return fromPlanId; }
    UUID toPlanId() { return toPlanId; }
    UUID actorUserId() { return actorUserId; }
    String reason() { return reason; }
    Instant occurredAt() { return occurredAt; }
}
