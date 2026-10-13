package com.occpl.platform.subscription.internal;

import com.occpl.platform.subscription.api.SubscriptionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface CompanySubscriptionRepository extends JpaRepository<CompanySubscription, UUID> {

    List<CompanySubscription> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);

    List<CompanySubscription> findByCompanyIdAndStatusIn(UUID companyId, Collection<SubscriptionStatus> statuses);

    Optional<CompanySubscription> findByIdAndCompanyId(UUID id, UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CompanySubscription s where s.id = :id")
    Optional<CompanySubscription> lockById(UUID id);

    @Query("""
            select s from CompanySubscription s
            where (:status is null or s.status = :status)
              and (:companyId is null or s.companyId = :companyId)
              and (:planId is null or s.planId = :planId)""")
    Page<CompanySubscription> search(SubscriptionStatus status, UUID companyId, UUID planId, Pageable pageable);

    // Sweep candidates (re-checked under a row lock before acting).
    @Query("select s.id from CompanySubscription s where s.status = 'TRIAL' and s.trialEnd <= :now order by s.trialEnd")
    List<UUID> trialsEnded(Instant now, Limit limit);

    @Query("select s.id from CompanySubscription s where s.status = 'ACTIVE' and s.currentPeriodEnd <= :now order by s.currentPeriodEnd")
    List<UUID> periodsEnded(Instant now, Limit limit);

    @Query("select s.id from CompanySubscription s where s.status = 'PAST_DUE' and s.currentPeriodEnd <= :graceStart order by s.currentPeriodEnd")
    List<UUID> graceElapsed(Instant graceStart, Limit limit);

    @Query("select s.id from CompanySubscription s where s.status = 'SUSPENDED' and s.statusChangedAt <= :suspendedBefore order by s.statusChangedAt")
    List<UUID> longSuspended(Instant suspendedBefore, Limit limit);
}
