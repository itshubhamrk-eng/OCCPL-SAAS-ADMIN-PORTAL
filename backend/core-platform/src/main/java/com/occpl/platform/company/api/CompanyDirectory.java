package com.occpl.platform.company.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read access to companies for other modules (tenant checks, membership listings, invitations). */
public interface CompanyDirectory {

    Optional<CompanySummary> find(UUID companyId);

    /** Batch lookup; ids without a company are absent from the result. */
    Map<UUID, CompanySummary> findAll(Collection<UUID> companyIds);

    /** Lower-case email domains allowed for invitations; empty means unrestricted. */
    List<String> allowedEmailDomains(UUID companyId);
}
