package com.occpl.platform.company.api;

import java.util.UUID;

/**
 * Who invoices are addressed to: the company's legal identity and its billing contact (falling back
 * to the primary contact).
 */
public record BillingProfile(UUID companyId, String legalName, String country, String taxId, String contactName,
        String contactEmail, String contactPhone) {
}
