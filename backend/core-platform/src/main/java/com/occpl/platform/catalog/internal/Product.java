package com.occpl.platform.catalog.internal;

import com.occpl.platform.catalog.api.ProductStatus;
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
@Table(name = "product")
public class Product {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    @Column(name = "app_url", nullable = false)
    private String appUrl;

    @Column(name = "api_base_url")
    private String apiBaseUrl;

    @Column(name = "api_audience", nullable = false)
    private String apiAudience;

    @Column(name = "service_client_id")
    private String serviceClientId;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

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

    protected Product() {
    }

    Product(UUID id, String code, String name, String description, String appUrl, String apiBaseUrl,
            String apiAudience, String serviceClientId, UUID actor, Instant now) {
        this.id = id;
        this.serviceClientId = serviceClientId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.appUrl = appUrl;
        this.apiBaseUrl = apiBaseUrl;
        this.apiAudience = apiAudience;
        this.status = ProductStatus.DRAFT;
        this.createdBy = actor;
        this.updatedBy = actor;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public enum Action {
        /** Also reinstates a retired product. Requires a published configuration. */
        ACTIVATE(ProductStatus.ACTIVE, EnumSet.of(ProductStatus.DRAFT, ProductStatus.RETIRED)),
        RETIRE(ProductStatus.RETIRED, EnumSet.of(ProductStatus.DRAFT, ProductStatus.ACTIVE));

        final ProductStatus target;
        final Set<ProductStatus> from;

        Action(ProductStatus target, Set<ProductStatus> from) {
            this.target = target;
            this.from = from;
        }
    }

    ProductStatus transition(Action action, UUID actor, Instant now) {
        if (!action.from.contains(status)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot " + action.name().toLowerCase() + " a product that is " + status + ".");
        }
        if (action == Action.ACTIVATE && currentVersionId == null) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Publish a configuration version before activating the product.");
        }
        ProductStatus previous = status;
        status = action.target;
        touch(actor, now);
        return previous;
    }

    void update(String name, String description, String appUrl, String apiBaseUrl, String apiAudience,
            String serviceClientId, UUID actor, Instant now) {
        this.serviceClientId = serviceClientId;
        this.name = name;
        this.description = description;
        this.appUrl = appUrl;
        this.apiBaseUrl = apiBaseUrl;
        this.apiAudience = apiAudience;
        touch(actor, now);
    }

    void makeCurrent(UUID versionId, UUID actor, Instant now) {
        this.currentVersionId = versionId;
        touch(actor, now);
    }

    private void touch(UUID actor, Instant now) {
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    UUID id() { return id; }
    String code() { return code; }
    String name() { return name; }
    String description() { return description; }
    ProductStatus status() { return status; }
    String appUrl() { return appUrl; }
    String apiBaseUrl() { return apiBaseUrl; }
    String apiAudience() { return apiAudience; }
    String serviceClientId() { return serviceClientId; }
    UUID currentVersionId() { return currentVersionId; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
    long version() { return version; }
}
