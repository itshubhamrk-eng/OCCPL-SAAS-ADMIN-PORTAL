package com.occpl.platform.company.api;

import java.util.UUID;

/**
 * Published synchronously inside the transaction that created a company; the membership module
 * invites the initial Company Admin in the same transaction.
 */
public record CompanyCreated(UUID companyId, String initialAdminEmail, String initialAdminFirstName,
        String initialAdminLastName) {
}
