package com.occpl.platform.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_profile")
class UserProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "display_name")
    private String displayName;

    private String phone;

    private String locale;

    @Column(name = "time_zone")
    private String timeZone;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected UserProfile() {
    }

    UserProfile(UUID userId, String displayName, Instant now) {
        this.userId = userId;
        this.displayName = displayName;
        this.updatedAt = now;
    }

    void update(String displayName, String phone, String locale, String timeZone, Instant now) {
        this.displayName = displayName;
        this.phone = phone;
        this.locale = locale;
        this.timeZone = timeZone;
        this.updatedAt = now;
    }

    String displayName() {
        return displayName;
    }

    String phone() {
        return phone;
    }

    String locale() {
        return locale;
    }

    String timeZone() {
        return timeZone;
    }

    long version() {
        return version;
    }
}
