package com.occpl.platform.provisioning.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ProvisioningJobRepository extends JpaRepository<ProvisioningJob, UUID> {

    /**
     * Due jobs: pending ones whose time has come and running ones whose lease expired (the instance
     * running them died). A job waits while another job of the same company and product is running,
     * so a provision and a deprovision never overlap. {@code SKIP LOCKED} lets instances run in parallel.
     */
    @Query(value = """
            select * from provisioning_job j
            where ((j.status = 'PENDING' and j.next_attempt_at <= :now)
                   or (j.status = 'RUNNING' and j.locked_until < :now))
              and not exists (select 1 from provisioning_job r
                              where r.company_id = j.company_id and r.product_id = j.product_id
                                and r.status = 'RUNNING' and r.locked_until >= :now and r.id <> j.id)
            order by j.next_attempt_at
            limit :limit
            for update skip locked""", nativeQuery = true)
    List<ProvisioningJob> claimDue(Instant now, int limit);

    @Query(value = """
            select * from provisioning_job
            where status = 'AWAITING_CALLBACK' and callback_deadline < :now
            order by callback_deadline
            limit :limit
            for update skip locked""", nativeQuery = true)
    List<ProvisioningJob> claimExpiredCallbacks(Instant now, int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ProvisioningJob j where j.id = :id")
    Optional<ProvisioningJob> lockById(UUID id);

    List<ProvisioningJob> findByCompanyIdAndProductIdAndOperationAndStatusIn(UUID companyId, UUID productId,
            ProvisioningJob.Operation operation, Collection<ProvisioningJob.Status> statuses);

    @Query("""
            select j from ProvisioningJob j
            where (:status is null or j.status = :status)
              and (:operation is null or j.operation = :operation)
              and (:companyId is null or j.companyId = :companyId)
              and (:productId is null or j.productId = :productId)""")
    Page<ProvisioningJob> search(ProvisioningJob.Status status, ProvisioningJob.Operation operation, UUID companyId,
            UUID productId, Pageable pageable);
}
