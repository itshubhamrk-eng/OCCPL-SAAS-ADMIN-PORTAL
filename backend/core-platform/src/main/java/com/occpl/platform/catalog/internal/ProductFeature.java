package com.occpl.platform.catalog.internal;

import com.occpl.platform.catalog.api.FeatureStatus;
import com.occpl.platform.catalog.api.FeatureSummary;
import com.occpl.platform.catalog.api.FeatureType;
import com.occpl.platform.catalog.api.FeatureUnit;
import com.occpl.platform.catalog.api.ResetPeriod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A capability plans can grant. Code, type, unit and reset period are immutable once created. */
@Entity
@Table(name = "product_feature")
class ProductFeature {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private FeatureType type;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private FeatureUnit unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "reset_period", updatable = false)
    private ResetPeriod resetPeriod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeatureStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ProductFeature() {
    }

    ProductFeature(UUID id, UUID productId, String code, String name, String description, FeatureType type,
            FeatureUnit unit, ResetPeriod resetPeriod, Instant now) {
        this.id = id;
        this.productId = productId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.type = type;
        this.unit = unit;
        this.resetPeriod = resetPeriod;
        this.status = FeatureStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void rename(String name, String description, Instant now) {
        this.name = name;
        this.description = description;
        this.updatedAt = now;
    }

    void changeStatus(FeatureStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    UUID id() { return id; }
    String code() { return code; }
    String name() { return name; }
    String description() { return description; }
    FeatureStatus status() { return status; }
    long version() { return version; }
    Instant createdAt() { return createdAt; }

    FeatureSummary toSummary() {
        return new FeatureSummary(id, productId, code, name, type, unit, resetPeriod, status);
    }
}
