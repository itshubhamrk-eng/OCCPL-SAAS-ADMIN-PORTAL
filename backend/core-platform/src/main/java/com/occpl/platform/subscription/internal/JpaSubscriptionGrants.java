package com.occpl.platform.subscription.internal;

import com.occpl.platform.subscription.api.SubscriptionGrants;
import com.occpl.platform.subscription.api.SubscriptionStatus;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaSubscriptionGrants implements SubscriptionGrants {

    private final CompanySubscriptionRepository subscriptions;
    private final PlanService plans;

    JpaSubscriptionGrants(CompanySubscriptionRepository subscriptions, PlanService plans) {
        this.subscriptions = subscriptions;
        this.plans = plans;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Grant> liveGrants(UUID companyId) {
        List<Grant> grants = new ArrayList<>();
        for (CompanySubscription s : subscriptions.findByCompanyIdAndStatusIn(companyId,
                EnumSet.of(SubscriptionStatus.TRIAL, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE,
                        SubscriptionStatus.SUSPENDED))) {
            SubscriptionPlan plan = plans.require(s.planId());
            Map<UUID, List<FeatureGrant>> byProduct = new HashMap<>();
            plan.productIds().forEach(p -> byProduct.put(p, new ArrayList<>()));
            plan.features().forEach(f -> byProduct.computeIfAbsent(f.productId(), k -> new ArrayList<>())
                    .add(new FeatureGrant(f.featureId(), f.enabled(), f.limitValue())));
            grants.add(new Grant(s.id(), s.status(), s.currentPeriodStart(), s.currentPeriodEnd(),
                    byProduct.entrySet().stream().map(e -> new ProductGrant(e.getKey(), List.copyOf(e.getValue())))
                            .toList()));
        }
        return grants;
    }
}
