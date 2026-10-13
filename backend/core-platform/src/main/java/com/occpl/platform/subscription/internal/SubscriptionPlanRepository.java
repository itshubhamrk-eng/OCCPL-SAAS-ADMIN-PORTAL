package com.occpl.platform.subscription.internal;

import com.occpl.platform.subscription.api.PlanStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    boolean existsByCode(String code);

    @Query("""
            select p from SubscriptionPlan p
            where (:status is null or p.status = :status)
              and (:q is null or p.code like :q escape '\\' or p.name like :q escape '\\')""")
    Page<SubscriptionPlan> search(PlanStatus status, String q, Pageable pageable);
}
