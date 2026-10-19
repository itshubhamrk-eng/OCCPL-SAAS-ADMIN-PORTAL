package com.occpl.platform.lead.internal;

import com.occpl.platform.lead.api.LeadStatus;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "sales_lead")
public class Lead {

    private static final Map<LeadStatus, Set<LeadStatus>> TRANSITIONS = Map.of(
            LeadStatus.NEW, EnumSet.of(LeadStatus.CONTACTED, LeadStatus.QUALIFIED, LeadStatus.LOST),
            LeadStatus.CONTACTED, EnumSet.of(LeadStatus.QUALIFIED, LeadStatus.LOST),
            LeadStatus.QUALIFIED, EnumSet.of(LeadStatus.CONVERTED, LeadStatus.LOST),
            LeadStatus.CONVERTED, EnumSet.noneOf(LeadStatus.class),
            LeadStatus.LOST, EnumSet.of(LeadStatus.NEW));

    @Id
    private UUID id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(nullable = false)
    private String email;

    private String phone;

    @Column(name = "product_interest")
    private String productInterest;

    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadStatus status;

    private String notes;

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

    protected Lead() {
    }

    Lead(UUID id, String companyName, String contactName, String email, String phone, String productInterest,
            String source, String notes, UUID actor, Instant now) {
        this.id = id;
        this.companyName = companyName;
        this.contactName = contactName;
        this.email = email;
        this.phone = phone;
        this.productInterest = productInterest;
        this.source = source;
        this.notes = notes;
        this.status = LeadStatus.NEW;
        this.createdBy = actor;
        this.updatedBy = actor;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String companyName, String contactName, String email, String phone, String productInterest,
            String source, String notes, UUID actor, Instant now) {
        this.companyName = companyName;
        this.contactName = contactName;
        this.email = email;
        this.phone = phone;
        this.productInterest = productInterest;
        this.source = source;
        this.notes = notes;
        touch(actor, now);
    }

    LeadStatus changeStatus(LeadStatus target, UUID actor, Instant now) {
        if (!TRANSITIONS.get(status).contains(target)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot move a lead from " + status + " to " + target + ".");
        }
        LeadStatus previous = status;
        status = target;
        touch(actor, now);
        return previous;
    }

    private void touch(UUID actor, Instant now) {
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    UUID id() { return id; }
    String companyName() { return companyName; }
    String contactName() { return contactName; }
    String email() { return email; }
    String phone() { return phone; }
    String productInterest() { return productInterest; }
    String source() { return source; }
    LeadStatus status() { return status; }
    String notes() { return notes; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
    long version() { return version; }
}
