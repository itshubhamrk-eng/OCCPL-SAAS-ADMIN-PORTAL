package com.occpl.platform.company.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "company_contact")
class CompanyContact {

    enum Type { PRIMARY, BILLING, TECHNICAL, SUPPORT }

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type type;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    private String phone;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CompanyContact() {
    }

    CompanyContact(UUID id, UUID companyId, Type type) {
        this.id = id;
        this.companyId = companyId;
        this.type = type;
    }

    void update(String name, String email, String phone, Instant now) {
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.updatedAt = now;
    }

    Type type() { return type; }
    String name() { return name; }
    String email() { return email; }
    String phone() { return phone; }
}
