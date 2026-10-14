package com.occpl.platform.billing.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByIdAndCompanyId(UUID id, UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.gateway = :gateway and i.gatewayInvoiceId = :gatewayInvoiceId")
    Optional<Invoice> lockByGatewayId(String gateway, String gatewayInvoiceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> lockById(UUID id);

    @Query("""
            select count(i) > 0 from Invoice i
            where i.subscriptionId = :subscriptionId and i.periodStart = :periodStart
              and i.status in ('CREATING', 'ISSUED', 'PARTIALLY_PAID', 'PAID')""")
    boolean existsOpenOrPaid(UUID subscriptionId, Instant periodStart);

    @Query("""
            select i from Invoice i
            where i.companyId = :companyId and (:status is null or i.status = :status)""")
    Page<Invoice> findByCompany(UUID companyId, Invoice.Status status, Pageable pageable);

    @Query("""
            select i from Invoice i
            where (:status is null or i.status = :status) and (:companyId is null or i.companyId = :companyId)""")
    Page<Invoice> search(Invoice.Status status, UUID companyId, Pageable pageable);

    @Query("select i from Invoice i where i.status = 'CREATING' and i.createdAt < :before")
    List<Invoice> stuckCreating(Instant before);
}
