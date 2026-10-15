package com.occpl.platform.company.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CompanyStatusChangeRepository extends JpaRepository<CompanyStatusChange, UUID> {

    List<CompanyStatusChange> findByCompanyIdOrderByChangedAtDesc(UUID companyId);
}
