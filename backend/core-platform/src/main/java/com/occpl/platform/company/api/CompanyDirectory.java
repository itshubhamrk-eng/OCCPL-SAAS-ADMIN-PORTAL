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

    /** The company's primary contact; empty when the company has none. */
    Optional<PrimaryContact> primaryContact(UUID companyId);

    /** Empty when the company does not exist or has neither a billing nor a primary contact. */
    Optional<BillingProfile> billingProfile(UUID companyId);
}
