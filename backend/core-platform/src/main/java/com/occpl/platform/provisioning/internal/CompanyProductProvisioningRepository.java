package com.occpl.platform.provisioning.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface CompanyProductProvisioningRepository
        extends JpaRepository<CompanyProductProvisioning, CompanyProductProvisioning.Key> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CompanyProductProvisioning p where p.companyId = :companyId and p.productId = :productId")
    Optional<CompanyProductProvisioning> lock(UUID companyId, UUID productId);

    List<CompanyProductProvisioning> findByCompanyId(UUID companyId);
}
