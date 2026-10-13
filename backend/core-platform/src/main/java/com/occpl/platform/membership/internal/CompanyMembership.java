package com.occpl.platform.membership.internal;

import com.occpl.platform.membership.api.MembershipStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A user's membership in a company. Lifecycle operations arrive with membership management (Phase 4). */
@Entity
@Table(name = "company_membership")
class CompanyMembership {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipStatus status;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CompanyMembership() {
    }

    UUID id() {
        return id;
    }

    UUID companyId() {
        return companyId;
    }

    MembershipStatus status() {
        return status;
    }
}
