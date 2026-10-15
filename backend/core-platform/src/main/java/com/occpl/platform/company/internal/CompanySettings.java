package com.occpl.platform.company.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "company_settings")
class CompanySettings {

    @Id
    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(nullable = false)
    private String locale;

    @Column(name = "allowed_email_domains", nullable = false)
    private String allowedEmailDomains;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    private long version;

    protected CompanySettings() {
    }

    CompanySettings(UUID companyId, String timeZone, String locale, UUID actor, Instant now) {
        this.companyId = companyId;
        this.timeZone = timeZone;
        this.locale = locale;
        this.allowedEmailDomains = "";
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    void update(String timeZone, String locale, List<String> domains, UUID actor, Instant now) {
        this.timeZone = timeZone;
        this.locale = locale;
        this.allowedEmailDomains = String.join(",", domains);
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    String timeZone() { return timeZone; }
    String locale() { return locale; }
    long version() { return version; }

    List<String> allowedEmailDomains() {
        return allowedEmailDomains.isBlank() ? List.of() : Arrays.asList(allowedEmailDomains.split(","));
    }
}
