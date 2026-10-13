package com.occpl.platform.entitlement.internal;

import com.occpl.platform.entitlement.api.EntitlementStatus;
import com.occpl.platform.shared.id.UuidV7;
import com.occpl.platform.subscription.api.SubscriptionChanged;
import com.occpl.platform.subscription.api.SubscriptionGrants;
import com.occpl.platform.subscription.api.SubscriptionGrants.FeatureGrant;
import com.occpl.platform.subscription.api.SubscriptionGrants.Grant;
import com.occpl.platform.subscription.api.SubscriptionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes a company's entitlements from its live subscriptions, in the same transaction as the
 * subscription change (so access and subscription state can never disagree):
 * <ul>
 *   <li>product status = best status among subscriptions granting it (ACTIVE > GRACE > SUSPENDED);</li>
 *   <li>only subscriptions at that best status contribute features;</li>
 *   <li>a feature is enabled if any contributing grant enables it; its limit is the largest
 *       contributing limit, and unlimited if any contributing grant is unlimited.</li>
 * </ul>
 */
@Component
class EntitlementRecomputer {

    private final SubscriptionGrants grants;
    private final EntitlementRepository entitlements;
    private final Clock clock;

    EntitlementRecomputer(SubscriptionGrants grants, EntitlementRepository entitlements, Clock clock) {
        this.grants = grants;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    @EventListener
    void onSubscriptionChanged(SubscriptionChanged event) {
        recompute(event.companyId());
    }

    static EntitlementStatus accessOf(SubscriptionStatus status) {
        return switch (status) {
            case TRIAL, ACTIVE -> EntitlementStatus.ACTIVE;
            case PAST_DUE -> EntitlementStatus.GRACE;
            default -> EntitlementStatus.SUSPENDED;
        };
    }

    @Transactional
    public void recompute(UUID companyId) {
        Instant now = clock.instant();
        Map<UUID, List<Grant>> byProduct = new HashMap<>();
        for (Grant grant : grants.liveGrants(companyId)) {
            grant.products().forEach(p -> byProduct.computeIfAbsent(p.productId(), k -> new ArrayList<>()).add(grant));
        }
        Map<UUID, CompanyProductEntitlement> existing = new HashMap<>();
        entitlements.findByCompanyId(companyId).forEach(e -> existing.put(e.productId(), e));

        for (Map.Entry<UUID, List<Grant>> entry : byProduct.entrySet()) {
            UUID productId = entry.getKey();
            EntitlementStatus best = entry.getValue().stream().map(g -> accessOf(g.status()))
                    .min(Enum::compareTo).orElseThrow();
            List<Grant> contributing = entry.getValue().stream().filter(g -> accessOf(g.status()) == best).toList();
            Instant validUntil = contributing.stream().map(Grant::currentPeriodEnd).max(Instant::compareTo).orElse(null);
            CompanyProductEntitlement entitlement = existing.remove(productId);
            if (entitlement == null) {
                entitlement = new CompanyProductEntitlement(UuidV7.generate(clock), companyId, productId);
            }
            entitlement.replace(best, validUntil, mergeFeatures(productId, contributing), now);
            entitlements.save(entitlement);
        }
        entitlements.deleteAll(existing.values());
        entitlements.flush();
    }

    private static Set<CompanyProductEntitlement.Feature> mergeFeatures(UUID productId, List<Grant> contributing) {
        record Merged(boolean enabled, boolean unlimited, Long limit, Instant anchor) {
        }
        Map<UUID, Merged> merged = new HashMap<>();
        for (Grant grant : contributing) {
            for (var product : grant.products()) {
                if (!product.productId().equals(productId)) {
                    continue;
                }
                for (FeatureGrant f : product.features()) {
                    Merged current = merged.get(f.featureId());
                    if (!f.enabled()) {
                        merged.putIfAbsent(f.featureId(), new Merged(false, false, 0L, grant.currentPeriodStart()));
                        continue;
                    }
                    boolean unlimited = f.limit() == null;
                    if (current == null || !current.enabled()) {
                        merged.put(f.featureId(), new Merged(true, unlimited, f.limit(), grant.currentPeriodStart()));
                    } else if (!current.unlimited() && (unlimited || f.limit() > current.limit())) {
                        merged.put(f.featureId(), new Merged(true, unlimited, f.limit(), grant.currentPeriodStart()));
                    }
                }
            }
        }
        Set<CompanyProductEntitlement.Feature> result = new HashSet<>();
        merged.forEach((featureId, m) -> result.add(new CompanyProductEntitlement.Feature(featureId, m.enabled(),
                m.enabled() && !m.unlimited() ? m.limit() : (m.enabled() ? null : 0L), m.anchor())));
        return result;
    }
}
