package com.occpl.platform.company.internal;

import com.occpl.platform.company.api.CompanyStatus;
import com.occpl.platform.company.api.CompanySummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Core company record. Lifecycle operations and validation are added with company management (Phase 4). */
@Entity
@Table(name = "company")
class Company {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanyStatus status;

    @Column(nullable = false, columnDefinition = "char(2)")
    private String country;

    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    private long version;

    protected Company() {
    }

    CompanySummary toSummary() {
        return new CompanySummary(id, code, displayName, status);
    }
}
