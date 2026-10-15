package com.occpl.platform.membership.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitation")
class Invitation {

    enum Status { PENDING, ACCEPTED, REVOKED, EXPIRED }

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "membership_id", nullable = false, updatable = false)
    private UUID membershipId;

    @Column(nullable = false, updatable = false)
    private String email;

    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Invitation() {
    }

    Invitation(UUID id, UUID companyId, UUID membershipId, String email, String tokenHash, Instant expiresAt,
            UUID invitedBy, Instant now) {
        this.id = id;
        this.companyId = companyId;
        this.membershipId = membershipId;
        this.email = email;
        this.tokenHash = tokenHash;
        this.status = Status.PENDING;
        this.expiresAt = expiresAt;
        this.invitedBy = invitedBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    boolean isExpired(Instant now) {
        return status == Status.EXPIRED || (status == Status.PENDING && !expiresAt.isAfter(now));
    }

    /** New token and expiry; also revives an expired invitation. */
    void reissue(String newTokenHash, Instant newExpiry, UUID by, Instant now) {
        tokenHash = newTokenHash;
        expiresAt = newExpiry;
        invitedBy = by;
        status = Status.PENDING;
        updatedAt = now;
    }

    void accept(Instant now) {
        status = Status.ACCEPTED;
        acceptedAt = now;
        updatedAt = now;
    }

    void revoke(Instant now) {
        status = Status.REVOKED;
        updatedAt = now;
    }

    void expire(Instant now) {
        status = Status.EXPIRED;
        updatedAt = now;
    }

    UUID id() { return id; }
    UUID companyId() { return companyId; }
    UUID membershipId() { return membershipId; }
    String email() { return email; }
    Status status() { return status; }
    Instant expiresAt() { return expiresAt; }
    UUID invitedBy() { return invitedBy; }
    Instant acceptedAt() { return acceptedAt; }
    Instant createdAt() { return createdAt; }
}
