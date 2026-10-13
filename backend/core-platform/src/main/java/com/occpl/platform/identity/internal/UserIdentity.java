package com.occpl.platform.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Links an IdP subject ({@code sub}) to a platform user. Immutable once created. */
@Entity
@Table(name = "user_identity")
class UserIdentity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserIdentity() {
    }

    UserIdentity(UUID id, UUID userId, String provider, String subject, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.subject = subject;
        this.createdAt = createdAt;
    }

    UUID userId() {
        return userId;
    }

    String subject() {
        return subject;
    }
}
