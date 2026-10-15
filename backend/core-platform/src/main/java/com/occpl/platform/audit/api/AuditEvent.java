package com.occpl.platform.audit.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An administrative action to record. {@code details} must never contain secrets, tokens or
 * unnecessary personal data; keep it to identifiers and changed values.
 */
public record AuditEvent(String action, String targetType, String targetId, UUID companyId, Outcome outcome,
        Map<String, Object> details) {

    public enum Outcome { SUCCESS, FAILURE }

    public AuditEvent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(outcome, "outcome");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static AuditEvent success(String action, String targetType, Object targetId, UUID companyId,
            Map<String, Object> details) {
        return new AuditEvent(action, targetType, targetId == null ? null : targetId.toString(), companyId,
                Outcome.SUCCESS, details);
    }
}
