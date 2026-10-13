package com.occpl.platform.entitlement.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface EntitlementRepository extends JpaRepository<CompanyProductEntitlement, UUID> {

    List<CompanyProductEntitlement> findByCompanyId(UUID companyId);

    Optional<CompanyProductEntitlement> findByCompanyIdAndProductId(UUID companyId, UUID productId);
}
