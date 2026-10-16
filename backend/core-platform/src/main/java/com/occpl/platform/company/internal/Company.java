package com.occpl.platform.company.internal;

import com.occpl.platform.company.api.CompanyStatus;
import com.occpl.platform.company.api.CompanySummary;
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

@Entity
@Table(name = "company")
public class Company {

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

    private String website;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    private String city;

    private String state;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(name = "gst_number")
    private String gstNumber;

    @Column(name = "pan_number")
    private String panNumber;

    @Column(name = "logo_key")
    private String logoKey;

    @Column(name = "favicon_key")
    private String faviconKey;

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

    Company(UUID id, String code, String legalName, String displayName, String country, String taxId, UUID actor,
            Instant now) {
        this.id = id;
        this.code = code;
        this.legalName = legalName;
        this.displayName = displayName;
        this.country = country;
        this.taxId = taxId;
        this.status = CompanyStatus.ACTIVE;
        this.createdBy = actor;
        this.updatedBy = actor;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Lifecycle actions and the states they may start from (DATABASE.md, company lifecycle). */
    public enum Action {
        ACTIVATE(CompanyStatus.ACTIVE, EnumSet.of(CompanyStatus.PENDING, CompanyStatus.SUSPENDED, CompanyStatus.DEACTIVATED)),
        SUSPEND(CompanyStatus.SUSPENDED, EnumSet.of(CompanyStatus.ACTIVE)),
        DEACTIVATE(CompanyStatus.DEACTIVATED, EnumSet.of(CompanyStatus.PENDING, CompanyStatus.ACTIVE, CompanyStatus.SUSPENDED));

        final CompanyStatus target;
        final Set<CompanyStatus> from;

        Action(CompanyStatus target, Set<CompanyStatus> from) {
            this.target = target;
            this.from = from;
        }
    }

    CompanyStatus transition(Action action, UUID actor, Instant now) {
        if (!action.from.contains(status)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot " + action.name().toLowerCase() + " a company that is " + status + ".");
        }
        CompanyStatus previous = status;
        status = action.target;
        touch(actor, now);
        return previous;
    }

    void update(String legalName, String displayName, String country, String taxId, UUID actor, Instant now) {
        this.legalName = legalName;
        this.displayName = displayName;
        this.country = country;
        this.taxId = taxId;
        touch(actor, now);
    }

    /** Sets the optional profile fields; null clears a field. */
    void updateProfile(String website, String addressLine1, String addressLine2, String city, String state,
            String postalCode, String gstNumber, String panNumber, UUID actor, Instant now) {
        this.website = website;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.gstNumber = gstNumber;
        this.panNumber = panNumber;
        touch(actor, now);
    }

    void setLogoKey(String key, UUID actor, Instant now) {
        this.logoKey = key;
        touch(actor, now);
    }

    void setFaviconKey(String key, UUID actor, Instant now) {
        this.faviconKey = key;
        touch(actor, now);
    }

    private void touch(UUID actor, Instant now) {
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    UUID id() { return id; }
    String code() { return code; }
    String legalName() { return legalName; }
    String displayName() { return displayName; }
    CompanyStatus status() { return status; }
    String country() { return country; }
    String taxId() { return taxId; }
    String website() { return website; }
    String addressLine1() { return addressLine1; }
    String addressLine2() { return addressLine2; }
    String city() { return city; }
    String state() { return state; }
    String postalCode() { return postalCode; }
    String gstNumber() { return gstNumber; }
    String panNumber() { return panNumber; }
    String logoKey() { return logoKey; }
    String faviconKey() { return faviconKey; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
    long version() { return version; }

    CompanySummary toSummary() {
        return new CompanySummary(id, code, displayName, status);
    }
}
