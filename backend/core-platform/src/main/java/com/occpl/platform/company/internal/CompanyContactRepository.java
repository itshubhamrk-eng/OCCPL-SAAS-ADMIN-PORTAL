package com.occpl.platform.company.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CompanyContactRepository extends JpaRepository<CompanyContact, UUID> {

    List<CompanyContact> findByCompanyIdOrderByType(UUID companyId);

    Optional<CompanyContact> findByCompanyIdAndType(UUID companyId, CompanyContact.Type type);
}
