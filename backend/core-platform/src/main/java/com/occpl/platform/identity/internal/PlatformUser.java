package com.occpl.platform.identity.internal;

import com.occpl.platform.identity.api.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "platform_user")
class PlatformUser {

    /** last_login_at is refreshed at most this often to avoid a write on every request. */
    static final Duration LAST_LOGIN_RESOLUTION = Duration.ofMinutes(15);

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PlatformUser() {
    }

    PlatformUser(UUID id, String email, UserStatus status, Instant now) {
        this.id = id;
        this.email = normalizeEmail(email);
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    UUID id() {
        return id;
    }

    String email() {
        return email;
    }

    UserStatus status() {
        return status;
    }

    void activate(Instant now) {
        if (status == UserStatus.INVITED) {
            status = UserStatus.ACTIVE;
            updatedAt = now;
        }
    }

    void changeEmail(String newEmail, Instant now) {
        email = normalizeEmail(newEmail);
        updatedAt = now;
    }

    /** Returns true if the login time was updated. */
    boolean recordLogin(Instant now) {
        if (lastLoginAt == null || lastLoginAt.plus(LAST_LOGIN_RESOLUTION).isBefore(now)) {
            lastLoginAt = now;
            return true;
        }
        return false;
    }
}
