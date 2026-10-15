package com.occpl.platform.entitlement.internal;

import com.occpl.platform.catalog.api.FeatureSummary;
import com.occpl.platform.catalog.api.FeatureType;
import com.occpl.platform.catalog.api.FeatureUnit;
import com.occpl.platform.catalog.api.ProductCatalog;
import com.occpl.platform.catalog.api.ProductSummary;
import com.occpl.platform.catalog.api.ResetPeriod;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanyStatus;
import com.occpl.platform.entitlement.api.EntitlementStatus;
import com.occpl.platform.entitlement.api.Entitlements;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Entitlement decisions and usage. Usage windows are in UTC: DAILY from midnight, MONTHLY from the
 * first of the month, BILLING_PERIOD from the granting subscription's period start, NONE (stock)
 * never resets.
 */
@Service
public class EntitlementService implements Entitlements {

    private final EntitlementRepository entitlements;
    private final UsageCounters counters;
    private final ProductCatalog catalog;
    private final CompanyDirectory companies;
    private final TransactionTemplate transactions;
    private final Clock clock;

    EntitlementService(EntitlementRepository entitlements, UsageCounters counters, ProductCatalog catalog,
            CompanyDirectory companies, PlatformTransactionManager transactionManager, Clock clock) {
        this.entitlements = entitlements;
        this.counters = counters;
        this.catalog = catalog;
        this.companies = companies;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public record ProductEntitlementView(UUID productId, String productCode, String productName, String appUrl,
            EntitlementStatus status, Instant validUntil, List<FeatureView> features) {
    }

    public record FeatureView(String code, String name, FeatureType type, FeatureUnit unit, ResetPeriod resetPeriod,
            boolean enabled, Long limit, Long used, Instant periodStart) {
    }

    @Override
    @Transactional(readOnly = true)
    public AccessDecision check(UUID companyId, String productCode, String featureCode) {
        Context ctx = resolve(companyId, productCode, featureCode);
        if (ctx.denial != null) {
            return ctx.denial;
        }
        if (ctx.feature == null) {
            return new AccessDecision(true, Reason.OK, ctx.entitlement.status(), null, null);
        }
        if (ctx.feature.type() == FeatureType.BOOLEAN) {
            return new AccessDecision(true, Reason.OK, ctx.entitlement.status(), null, null);
        }
        long used = counters.used(companyId, ctx.feature.id(), periodStart(ctx));
        Long limit = ctx.grant.limitValue();
        boolean allowed = limit == null || used < limit;
        return new AccessDecision(allowed, allowed ? Reason.OK : Reason.LIMIT_REACHED, ctx.entitlement.status(), limit,
                used);
    }

    @Override
    public UsageResult consume(UUID companyId, String productCode, String featureCode, long quantity,
            String idempotencyKey) {
        return changeUsage(companyId, productCode, featureCode, quantity, idempotencyKey, true);
    }

    @Override
    public UsageResult release(UUID companyId, String productCode, String featureCode, long quantity,
            String idempotencyKey) {
        return changeUsage(companyId, productCode, featureCode, quantity, idempotencyKey, false);
    }

    private UsageResult changeUsage(UUID companyId, String productCode, String featureCode, long quantity,
            String key, boolean consume) {
        if (quantity <= 0) {
            throw ApiException.validation("quantity must be greater than 0.");
        }
        if (key == null || key.isBlank() || key.length() > 100) {
            throw ApiException.validation("An idempotency key of 1-100 characters is required.");
        }
        try {
            return transactions.execute(status -> {
                Optional<UsageCounters.Replay> previous = counters.replay(companyId, key);
                Context ctx = resolve(companyId, productCode, featureCode);
                if (previous.isPresent()) {
                    return new UsageResult(previous.get().usedAfter(), ctx.grant == null ? null : ctx.grant.limitValue(),
                            true);
                }
                requireUsable(ctx, featureCode);
                Long limit = ctx.grant.limitValue();
                long used = consume
                        ? counters.consume(companyId, ctx.feature.id(), periodStart(ctx), quantity, limit, key)
                        : counters.release(companyId, ctx.feature.id(), periodStart(ctx), quantity, key);
                return new UsageResult(used, limit, false);
            });
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            // Another request with the same key committed first; return its outcome.
            UsageCounters.Replay replay = counters.replay(companyId, key).orElseThrow(() -> concurrentDuplicate);
            return new UsageResult(replay.usedAfter(), null, true);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalLong memberLimit(UUID companyId) {
        Long max = null;
        boolean anySeatFeature = false;
        for (CompanyProductEntitlement entitlement : entitlements.findByCompanyId(companyId)) {
            if (!entitlement.status().allowsAccess()) {
                continue;
            }
            for (FeatureSummary feature : catalog.features(entitlement.productId())) {
                if (feature.unit() != FeatureUnit.SEATS) {
                    continue;
                }
                Optional<CompanyProductEntitlement.Feature> grant = entitlement.feature(feature.id());
                if (grant.isEmpty() || !grant.get().enabled()) {
                    continue;
                }
                if (grant.get().limitValue() == null) {
                    return OptionalLong.empty(); // an unlimited seat grant
                }
                anySeatFeature = true;
                max = max == null ? grant.get().limitValue() : Math.max(max, grant.get().limitValue());
            }
        }
        return anySeatFeature ? OptionalLong.of(max) : OptionalLong.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductEntitlement> products(UUID companyId) {
        return entitlements.findByCompanyId(companyId).stream()
                .map(e -> new ProductEntitlement(e.productId(), e.status(), e.validUntil())).toList();
    }

    @Transactional(readOnly = true)
    public List<ProductEntitlementView> forCompany(UUID companyId) {
        return entitlements.findByCompanyId(companyId).stream().map(e -> {
            ProductSummary product = catalog.find(e.productId()).orElseThrow();
            List<FeatureView> features = catalog.features(e.productId()).stream()
                    .map(f -> e.feature(f.id()).map(g -> featureView(companyId, e, f, g)).orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(FeatureView::code)).toList();
            return new ProductEntitlementView(product.id(), product.code(), product.name(), product.appUrl(),
                    e.status(), e.validUntil(), features);
        }).sorted(Comparator.comparing(ProductEntitlementView::productCode)).toList();
    }

    private FeatureView featureView(UUID companyId, CompanyProductEntitlement e, FeatureSummary f,
            CompanyProductEntitlement.Feature g) {
        if (f.type() == FeatureType.BOOLEAN) {
            return new FeatureView(f.code(), f.name(), f.type(), null, null, g.enabled(), null, null, null);
        }
        Instant start = periodStart(f.resetPeriod(), g.periodAnchor());
        return new FeatureView(f.code(), f.name(), f.type(), f.unit(), f.resetPeriod(), g.enabled(), g.limitValue(),
                counters.used(companyId, f.id(), start), start.equals(UsageCounters.STOCK_PERIOD) ? null : start);
    }

    // ------------------------------------------------------------------ resolution

    private static final class Context {
        AccessDecision denial;
        CompanyProductEntitlement entitlement;
        FeatureSummary feature;
        CompanyProductEntitlement.Feature grant;
    }

    private Context resolve(UUID companyId, String productCode, String featureCode) {
        Context ctx = new Context();
        CompanyStatus companyStatus = companies.find(companyId).map(c -> c.status()).orElse(null);
        if (companyStatus != CompanyStatus.ACTIVE) {
            ctx.denial = new AccessDecision(false, Reason.COMPANY_INACTIVE, null, null, null);
            return ctx;
        }
        Optional<ProductSummary> product = catalog.findByCode(productCode);
        ctx.entitlement = product.flatMap(p -> entitlements.findByCompanyIdAndProductId(companyId, p.id())).orElse(null);
        if (ctx.entitlement == null) {
            ctx.denial = new AccessDecision(false, Reason.NO_ENTITLEMENT, null, null, null);
            return ctx;
        }
        if (!ctx.entitlement.status().allowsAccess()) {
            ctx.denial = new AccessDecision(false, Reason.SUSPENDED, ctx.entitlement.status(), null, null);
            return ctx;
        }
        if (featureCode == null) {
            return ctx;
        }
        Map<String, FeatureSummary> features = catalog.features(product.get().id()).stream()
                .collect(Collectors.toMap(FeatureSummary::code, Function.identity()));
        ctx.feature = features.get(featureCode);
        ctx.grant = ctx.feature == null ? null : ctx.entitlement.feature(ctx.feature.id()).orElse(null);
        if (ctx.grant == null || !ctx.grant.enabled()) {
            ctx.denial = new AccessDecision(false, Reason.FEATURE_NOT_INCLUDED, ctx.entitlement.status(), null, null);
        }
        return ctx;
    }

    private static void requireUsable(Context ctx, String featureCode) {
        if (ctx.denial != null) {
            throw new ApiException(ErrorCode.ENTITLEMENT_REQUIRED,
                    "The company is not entitled to use this feature (" + ctx.denial.reason() + ").");
        }
        if (ctx.feature == null || ctx.feature.type() != FeatureType.LIMIT) {
            throw ApiException.validation("Feature '" + featureCode + "' is not a usage-limited feature.");
        }
    }

    private Instant periodStart(Context ctx) {
        return periodStart(ctx.feature.resetPeriod(), ctx.grant.periodAnchor());
    }

    private Instant periodStart(ResetPeriod period, Instant anchor) {
        Instant now = clock.instant();
        return switch (period) {
            case NONE -> UsageCounters.STOCK_PERIOD;
            case DAILY -> now.truncatedTo(ChronoUnit.DAYS);
            case MONTHLY -> now.atOffset(ZoneOffset.UTC).with(TemporalAdjusters.firstDayOfMonth())
                    .truncatedTo(ChronoUnit.DAYS).toInstant();
            case BILLING_PERIOD -> anchor == null ? UsageCounters.STOCK_PERIOD : anchor.truncatedTo(ChronoUnit.MICROS);
        };
    }
}
