package com.occpl.platform.company.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read access to companies for other modules (tenant checks, membership listings). */
public interface CompanyDirectory {

    Optional<CompanySummary> find(UUID companyId);

    /** Batch lookup; ids without a company are absent from the result. */
    Map<UUID, CompanySummary> findAll(Collection<UUID> companyIds);
}
