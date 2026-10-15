package com.occpl.platform.audit.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
class AuditLogEntry {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_client_id")
    private String actorClientId;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(nullable = false)
    private String action;

    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "target_id")
    private String targetId;

    @Column(nullable = false)
    private String outcome;

    @Column(name = "correlation_id")
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private String details;

    protected AuditLogEntry() {
    }

    AuditLogEntry(UUID id, Instant occurredAt, String actorType, UUID actorUserId, String actorClientId,
            UUID companyId, String action, String targetType, String targetId, String outcome, String correlationId,
            String details) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.actorType = actorType;
        this.actorUserId = actorUserId;
        this.actorClientId = actorClientId;
        this.companyId = companyId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.outcome = outcome;
        this.correlationId = correlationId;
        this.details = details;
    }

    UUID actorUserId() {
        return actorUserId;
    }

    String action() {
        return action;
    }

    String details() {
        return details;
    }
}
