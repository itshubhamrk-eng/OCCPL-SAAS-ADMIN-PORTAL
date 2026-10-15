package com.occpl.platform.company.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CompanySettingsRepository extends JpaRepository<CompanySettings, UUID> {
}
