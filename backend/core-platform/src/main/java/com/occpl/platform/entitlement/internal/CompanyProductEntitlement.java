package com.occpl.platform.entitlement.internal;

import com.occpl.platform.entitlement.api.EntitlementStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Derived data: recomputed from live subscriptions; never edited directly. */
@Entity
@Table(name = "company_product_entitlement")
class CompanyProductEntitlement {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntitlementStatus status;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "entitlement_feature", joinColumns = @JoinColumn(name = "entitlement_id"))
    private Set<Feature> features = new HashSet<>();

    protected CompanyProductEntitlement() {
    }

    CompanyProductEntitlement(UUID id, UUID companyId, UUID productId) {
        this.id = id;
        this.companyId = companyId;
        this.productId = productId;
    }

    /** {@code limitValue} null = unlimited. {@code periodAnchor} is the BILLING_PERIOD window start. */
    @Embeddable
    record Feature(@Column(name = "feature_id") UUID featureId, @Column(name = "enabled") boolean enabled,
            @Column(name = "limit_value") Long limitValue, @Column(name = "period_anchor") Instant periodAnchor) {
    }

    void replace(EntitlementStatus status, Instant validUntil, Set<Feature> features, Instant now) {
        this.status = status;
        this.validUntil = validUntil;
        this.features.clear();
        this.features.addAll(features);
        this.computedAt = now;
    }

    Optional<Feature> feature(UUID featureId) {
        return features.stream().filter(f -> f.featureId().equals(featureId)).findFirst();
    }

    UUID id() { return id; }
    UUID companyId() { return companyId; }
    UUID productId() { return productId; }
    EntitlementStatus status() { return status; }
    Instant validUntil() { return validUntil; }
    Set<Feature> features() { return Set.copyOf(features); }
}
