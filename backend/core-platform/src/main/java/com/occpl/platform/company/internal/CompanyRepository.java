package com.occpl.platform.company.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CompanyRepository extends JpaRepository<Company, UUID> {
}
