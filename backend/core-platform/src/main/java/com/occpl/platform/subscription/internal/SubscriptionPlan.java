package com.occpl.platform.subscription.internal;

import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.subscription.api.BillingPeriod;
import com.occpl.platform.subscription.api.PlanStatus;
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
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A sellable plan: prices per currency, included products and feature grants. Immutable once ACTIVE. */
@Entity
@Table(name = "subscription_plan")
public class SubscriptionPlan {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false)
    private BillingPeriod billingPeriod;

    @Column(name = "trial_days", nullable = false)
    private int trialDays;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "plan_price", joinColumns = @JoinColumn(name = "plan_id"))
    @MapKeyColumn(name = "currency", columnDefinition = "char(3)")
    @Column(name = "amount_minor")
    private Map<String, Long> prices = new HashMap<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "plan_product", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "product_id")
    private Set<UUID> productIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "plan_feature", joinColumns = @JoinColumn(name = "plan_id"))
    private Set<FeatureGrant> features = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Version
    private long version;

    protected SubscriptionPlan() {
    }

    SubscriptionPlan(UUID id, String code, UUID createdBy, Instant now) {
        this.id = id;
        this.code = code;
        this.status = PlanStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @Embeddable
    record FeatureGrant(@Column(name = "product_id") UUID productId, @Column(name = "feature_id") UUID featureId,
            @Column(name = "enabled") boolean enabled, @Column(name = "limit_value") Long limitValue) {
    }

    void replaceContent(String name, String description, BillingPeriod billingPeriod, int trialDays,
            Map<String, Long> prices, Set<UUID> productIds, Set<FeatureGrant> features, Instant now) {
        requireDraft();
        this.name = name;
        this.description = description;
        this.billingPeriod = billingPeriod;
        this.trialDays = trialDays;
        this.prices.clear();
        this.prices.putAll(prices);
        this.productIds.clear();
        this.productIds.addAll(productIds);
        this.features.clear();
        this.features.addAll(features);
        this.updatedAt = now;
    }

    void publish(Instant now) {
        requireDraft();
        status = PlanStatus.ACTIVE;
        publishedAt = now;
        updatedAt = now;
    }

    void archive(Instant now) {
        if (status != PlanStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only active plans can be archived.");
        }
        status = PlanStatus.ARCHIVED;
        updatedAt = now;
    }

    private void requireDraft() {
        if (status != PlanStatus.DRAFT) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Only draft plans can be changed. Copy the plan to create a new version.");
        }
    }

    UUID id() { return id; }
    String code() { return code; }
    String name() { return name; }
    String description() { return description; }
    PlanStatus status() { return status; }
    BillingPeriod billingPeriod() { return billingPeriod; }
    int trialDays() { return trialDays; }
    Map<String, Long> prices() { return Map.copyOf(prices); }
    Set<UUID> productIds() { return Set.copyOf(productIds); }
    Set<FeatureGrant> features() { return Set.copyOf(features); }
    Instant createdAt() { return createdAt; }
    Instant publishedAt() { return publishedAt; }
    long version() { return version; }
}
