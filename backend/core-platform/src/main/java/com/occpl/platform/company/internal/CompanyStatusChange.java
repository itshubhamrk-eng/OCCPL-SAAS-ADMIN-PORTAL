package com.occpl.platform.company.internal;

import com.occpl.platform.company.api.CompanyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only lifecycle history of a company. */
@Entity
@Table(name = "company_status_history")
class CompanyStatusChange {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private CompanyStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private CompanyStatus toStatus;

    @Column(updatable = false)
    private String reason;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected CompanyStatusChange() {
    }

    CompanyStatusChange(UUID id, UUID companyId, CompanyStatus fromStatus, CompanyStatus toStatus, String reason,
            UUID changedBy, Instant changedAt) {
        this.id = id;
        this.companyId = companyId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    CompanyStatus fromStatus() { return fromStatus; }
    CompanyStatus toStatus() { return toStatus; }
    String reason() { return reason; }
    UUID changedBy() { return changedBy; }
    Instant changedAt() { return changedAt; }
}
