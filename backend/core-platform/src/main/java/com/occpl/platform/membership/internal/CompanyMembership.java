package com.occpl.platform.membership.internal;

import com.occpl.platform.membership.api.MembershipStatus;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * A user's membership in a company. Lifecycle: INVITED → ACTIVE (accept) ⇄ SUSPENDED; any state →
 * DEACTIVATED; DEACTIVATED or INVITED → INVITED again when re-invited.
 */
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

    CompanyMembership(UUID id, UUID companyId, UUID userId, Instant now) {
        this.id = id;
        this.companyId = companyId;
        this.userId = userId;
        this.status = MembershipStatus.INVITED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void reinvite(Instant now) {
        move(EnumSet.of(MembershipStatus.INVITED, MembershipStatus.DEACTIVATED), MembershipStatus.INVITED, "re-invite", now);
    }

    void accept(Instant now) {
        move(EnumSet.of(MembershipStatus.INVITED), MembershipStatus.ACTIVE, "accept", now);
        joinedAt = now;
    }

    void suspend(Instant now) {
        move(EnumSet.of(MembershipStatus.ACTIVE), MembershipStatus.SUSPENDED, "suspend", now);
    }

    void reactivate(Instant now) {
        move(EnumSet.of(MembershipStatus.SUSPENDED), MembershipStatus.ACTIVE, "reactivate", now);
    }

    void deactivate(Instant now) {
        move(EnumSet.of(MembershipStatus.INVITED, MembershipStatus.ACTIVE, MembershipStatus.SUSPENDED),
                MembershipStatus.DEACTIVATED, "deactivate", now);
    }

    private void move(Set<MembershipStatus> from, MembershipStatus to, String action, Instant now) {
        if (!from.contains(status)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot " + action + " a membership that is " + status + ".");
        }
        status = to;
        updatedAt = now;
    }

    UUID id() { return id; }
    UUID companyId() { return companyId; }
    UUID userId() { return userId; }
    MembershipStatus status() { return status; }
    Instant joinedAt() { return joinedAt; }
    Instant createdAt() { return createdAt; }
}
