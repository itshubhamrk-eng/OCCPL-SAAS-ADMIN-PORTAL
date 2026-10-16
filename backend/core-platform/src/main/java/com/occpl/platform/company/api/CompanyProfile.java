package com.occpl.platform.company.api;

/**
 * The company's optional profile as sent to products when they provision it. Image links are signed and
 * expire (24 hours), so a product must fetch them when it receives them.
 */
public record CompanyProfile(String country, String website, String addressLine1, String addressLine2, String city,
        String state, String postalCode, String gstNumber, String panNumber, String logoUrl, String faviconUrl,
        PrimaryContact support) {
}
