package com.occpl.platform.subscription.internal;

import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.catalog.api.FeatureStatus;
import com.occpl.platform.catalog.api.FeatureSummary;
import com.occpl.platform.catalog.api.FeatureType;
import com.occpl.platform.catalog.api.FeatureUnit;
import com.occpl.platform.catalog.api.ProductCatalog;
import com.occpl.platform.catalog.api.ProductStatus;
import com.occpl.platform.catalog.api.ProductSummary;
import com.occpl.platform.catalog.api.ResetPeriod;
import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.error.Problems.FieldViolation;
import com.occpl.platform.shared.error.ValidationException;
import com.occpl.platform.shared.id.UuidV7;
import com.occpl.platform.shared.paging.Likes;
import com.occpl.platform.shared.paging.PageRequests;
import com.occpl.platform.subscription.api.BillingPeriod;
import com.occpl.platform.subscription.api.PlanStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Subscription plans. Endpoint authorization is enforced by the controller. */
@Service
public class PlanService {

    private static final Map<String, String> SORTS = Map.of("name", "name", "code", "code", "createdAt", "createdAt",
            "status", "status");

    private final SubscriptionPlanRepository plans;
    private final ProductCatalog catalog;
    private final AuditLogger audit;
    private final Clock clock;

    PlanService(SubscriptionPlanRepository plans, ProductCatalog catalog, AuditLogger audit, Clock clock) {
        this.plans = plans;
        this.catalog = catalog;
        this.audit = audit;
        this.clock = clock;
    }

    public record PlanContent(String name, String description, BillingPeriod billingPeriod, int trialDays,
            List<Price> prices, List<ProductContent> products) {
    }

    public record Price(String currency, long amountMinor) {
    }

    public record ProductContent(UUID productId, List<FeatureContent> features) {
    }

    /** {@code limit} null on a LIMIT feature means unlimited. */
    public record FeatureContent(String code, Boolean enabled, Long limit) {
    }

    public record PlanView(UUID id, String code, String name, String description, PlanStatus status,
            BillingPeriod billingPeriod, int trialDays, List<Price> prices, List<ProductView> products,
            Instant createdAt, Instant publishedAt, long version) {
    }

    public record ProductView(UUID productId, String productCode, String productName, List<FeatureView> features) {
    }

    public record FeatureView(UUID featureId, String code, String name, FeatureType type, FeatureUnit unit,
            ResetPeriod resetPeriod, boolean enabled, Long limit) {
    }

    @Transactional
    public PlanView create(String code, PlanContent content) {
        if (plans.existsByCode(code)) {
            throw new ApiException(ErrorCode.CONFLICT, "Plan code '" + code + "' is already in use.");
        }
        SubscriptionPlan plan = new SubscriptionPlan(UuidV7.generate(clock), code, CurrentUser.require().userId(),
                clock.instant());
        apply(plan, content);
        plans.saveAndFlush(plan);
        audit.record(AuditEvent.success("plan.created", "plan", plan.id(), null, Map.of("code", code)));
        return view(plan);
    }

    @Transactional
    public PlanView update(UUID planId, PlanContent content) {
        SubscriptionPlan plan = require(planId);
        apply(plan, content);
        plans.flush();
        audit.record(AuditEvent.success("plan.updated", "plan", planId, null, Map.of("code", plan.code())));
        return view(plan);
    }

    /** Publishing freezes the plan; it must sell at least one active product at a price. */
    @Transactional
    public PlanView publish(UUID planId) {
        SubscriptionPlan plan = require(planId);
        List<FieldViolation> violations = new ArrayList<>();
        if (plan.prices().isEmpty()) {
            violations.add(new FieldViolation("prices", "NotEmpty", "a plan needs at least one price"));
        }
        if (plan.productIds().isEmpty()) {
            violations.add(new FieldViolation("products", "NotEmpty", "a plan needs at least one product"));
        }
        for (UUID productId : plan.productIds()) {
            ProductSummary product = catalog.find(productId).orElseThrow();
            if (product.status() != ProductStatus.ACTIVE) {
                violations.add(new FieldViolation("products", "inactiveProduct",
                        "product '" + product.code() + "' is " + product.status() + "; only active products can be sold"));
            }
            Map<UUID, FeatureSummary> features = catalog.features(productId).stream()
                    .collect(Collectors.toMap(FeatureSummary::id, Function.identity()));
            for (SubscriptionPlan.FeatureGrant grant : plan.features()) {
                FeatureSummary feature = features.get(grant.featureId());
                if (feature != null && feature.status() == FeatureStatus.DEPRECATED) {
                    violations.add(new FieldViolation("products", "deprecatedFeature",
                            "feature '" + feature.code() + "' is deprecated"));
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("The plan cannot be published.", violations);
        }
        plan.publish(clock.instant());
        plans.flush();
        audit.record(AuditEvent.success("plan.published", "plan", planId, null, Map.of("code", plan.code())));
        return view(plan);
    }

    @Transactional
    public PlanView archive(UUID planId) {
        SubscriptionPlan plan = require(planId);
        plan.archive(clock.instant());
        plans.flush();
        audit.record(AuditEvent.success("plan.archived", "plan", planId, null, Map.of("code", plan.code())));
        return view(plan);
    }

    /** Starts a new draft from any plan; the way to "change" an active plan. */
    @Transactional
    public PlanView copy(UUID planId, String newCode, String newName) {
        SubscriptionPlan source = require(planId);
        if (plans.existsByCode(newCode)) {
            throw new ApiException(ErrorCode.CONFLICT, "Plan code '" + newCode + "' is already in use.");
        }
        SubscriptionPlan copy = new SubscriptionPlan(UuidV7.generate(clock), newCode, CurrentUser.require().userId(),
                clock.instant());
        copy.replaceContent(newName == null ? source.name() : newName, source.description(), source.billingPeriod(),
                source.trialDays(), source.prices(), source.productIds(), source.features(), clock.instant());
        plans.saveAndFlush(copy);
        audit.record(AuditEvent.success("plan.copied", "plan", copy.id(), null,
                Map.of("code", newCode, "sourceCode", source.code())));
        return view(copy);
    }

    @Transactional(readOnly = true)
    public PlanView get(UUID planId) {
        return view(require(planId));
    }

    @Transactional(readOnly = true)
    public Page<PlanView> search(PlanStatus status, String q, Integer page, Integer size, List<String> sort) {
        return plans.search(status, Likes.prefix(q), PageRequests.of(page, size, sort, SORTS,
                Sort.by(Sort.Direction.ASC, "name"))).map(this::view);
    }

    SubscriptionPlan require(UUID planId) {
        return plans.findById(planId).orElseThrow(() -> ApiException.notFound("Plan"));
    }

    private void apply(SubscriptionPlan plan, PlanContent content) {
        List<FieldViolation> violations = new ArrayList<>();
        Map<String, Long> prices = new LinkedHashMap<>();
        for (int i = 0; i < content.prices().size(); i++) {
            Price price = content.prices().get(i);
            String currency = price.currency().toUpperCase();
            if (!isCurrency(currency)) {
                violations.add(new FieldViolation("prices[" + i + "].currency", "currency", "unknown ISO 4217 currency"));
            } else if (prices.put(currency, price.amountMinor()) != null) {
                violations.add(new FieldViolation("prices[" + i + "].currency", "unique", "duplicate currency " + currency));
            }
        }
        Set<UUID> productIds = new HashSet<>();
        Set<SubscriptionPlan.FeatureGrant> grants = new HashSet<>();
        for (int p = 0; p < content.products().size(); p++) {
            ProductContent product = content.products().get(p);
            String path = "products[" + p + "]";
            if (!productIds.add(product.productId())) {
                violations.add(new FieldViolation(path + ".productId", "unique", "duplicate product"));
                continue;
            }
            if (catalog.find(product.productId()).isEmpty()) {
                violations.add(new FieldViolation(path + ".productId", "exists", "unknown product"));
                continue;
            }
            Map<String, FeatureSummary> features = catalog.features(product.productId()).stream()
                    .collect(Collectors.toMap(FeatureSummary::code, Function.identity()));
            Set<String> seen = new HashSet<>();
            for (int f = 0; f < product.features().size(); f++) {
                FeatureContent requested = product.features().get(f);
                String fpath = path + ".features[" + f + "]";
                FeatureSummary feature = features.get(requested.code());
                if (feature == null) {
                    violations.add(new FieldViolation(fpath + ".code", "exists", "unknown feature '" + requested.code() + "'"));
                    continue;
                }
                if (!seen.add(requested.code())) {
                    violations.add(new FieldViolation(fpath + ".code", "unique", "duplicate feature"));
                    continue;
                }
                if (feature.type() == FeatureType.BOOLEAN && requested.limit() != null) {
                    violations.add(new FieldViolation(fpath + ".limit", "notAllowed", "on/off features have no limit"));
                }
                if (feature.type() == FeatureType.LIMIT && requested.limit() != null && requested.limit() < 0) {
                    violations.add(new FieldViolation(fpath + ".limit", "Min", "must be 0 or more (omit for unlimited)"));
                }
                boolean enabled = requested.enabled() == null || requested.enabled();
                grants.add(new SubscriptionPlan.FeatureGrant(product.productId(), feature.id(), enabled,
                        feature.type() == FeatureType.LIMIT ? requested.limit() : null));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("The plan is invalid.", violations);
        }
        plan.replaceContent(content.name(), content.description(), content.billingPeriod(), content.trialDays(),
                prices, productIds, grants, clock.instant());
    }

    private static boolean isCurrency(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    PlanView view(SubscriptionPlan plan) {
        Map<UUID, List<SubscriptionPlan.FeatureGrant>> byProduct = new HashMap<>();
        plan.features().forEach(g -> byProduct.computeIfAbsent(g.productId(), k -> new ArrayList<>()).add(g));
        List<ProductView> products = plan.productIds().stream().map(productId -> {
            ProductSummary product = catalog.find(productId).orElseThrow();
            Map<UUID, FeatureSummary> features = catalog.features(productId).stream()
                    .collect(Collectors.toMap(FeatureSummary::id, Function.identity()));
            List<FeatureView> featureViews = byProduct.getOrDefault(productId, List.of()).stream().map(g -> {
                FeatureSummary f = features.get(g.featureId());
                return new FeatureView(f.id(), f.code(), f.name(), f.type(), f.unit(), f.resetPeriod(), g.enabled(),
                        g.limitValue());
            }).sorted(Comparator.comparing(FeatureView::code)).toList();
            return new ProductView(productId, product.code(), product.name(), featureViews);
        }).sorted(Comparator.comparing(ProductView::productCode)).toList();
        List<Price> prices = plan.prices().entrySet().stream().map(e -> new Price(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Price::currency)).toList();
        return new PlanView(plan.id(), plan.code(), plan.name(), plan.description(), plan.status(),
                plan.billingPeriod(), plan.trialDays(), prices, products, plan.createdAt(), plan.publishedAt(),
                plan.version());
    }
}
