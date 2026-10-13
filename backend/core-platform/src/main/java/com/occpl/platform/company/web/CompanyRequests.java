package com.occpl.platform.company.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;

/** Request bodies of the company API. */
final class CompanyRequests {

    static final String CODE = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    static final String COUNTRY = "[A-Z]{2}";
    static final String PHONE = "\\+[1-9][0-9]{6,14}";
    static final String LOCALE = "[a-z]{2,3}(-[A-Z]{2})?";
    static final String DOMAIN = "(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}";

    private CompanyRequests() {
    }

    record Contact(@NotBlank @Size(max = 200) String name, @NotBlank @Email @Size(max = 320) String email,
            @Pattern(regexp = PHONE, message = "must be in international format, e.g. +919876543210") String phone) {
    }

    record InitialAdmin(@NotBlank @Email @Size(max = 320) String email, @Size(max = 100) String firstName,
            @Size(max = 100) String lastName) {
    }

    record CreateCompany(
            @NotBlank @Pattern(regexp = CODE, message = "lower-case letters, digits and hyphens, 1-63 characters") String code,
            @NotBlank @Size(max = 200) String legalName,
            @NotBlank @Size(max = 200) String displayName,
            @NotBlank @Pattern(regexp = COUNTRY, message = "must be an ISO 3166-1 alpha-2 code, e.g. IN") String country,
            @Size(max = 64) String taxId,
            @Size(max = 64) String timeZone,
            @Pattern(regexp = LOCALE) String locale,
            @Valid Contact primaryContact,
            @NotNull @Valid InitialAdmin initialAdmin) {

        @AssertTrue(message = "timeZone must be a valid IANA time zone such as Asia/Kolkata")
        boolean isTimeZoneValid() {
            return validZone(timeZone);
        }
    }

    record UpdateCompany(@Size(min = 1, max = 200) String legalName, @Size(min = 1, max = 200) String displayName,
            @Pattern(regexp = COUNTRY) String country, @Size(max = 64) String taxId) {
    }

    record StatusChange(@Size(max = 500) String reason) {
    }

    record ReasonRequired(@NotBlank @Size(max = 500) String reason) {
    }

    record Settings(@NotBlank @Size(max = 64) String timeZone, @NotBlank @Pattern(regexp = LOCALE) String locale,
            @NotNull @Size(max = 20) List<@NotBlank @Pattern(regexp = DOMAIN, message = "must be a lower-case domain such as example.com") String> allowedEmailDomains) {

        @AssertTrue(message = "timeZone must be a valid IANA time zone such as Asia/Kolkata")
        boolean isTimeZoneValid() {
            return validZone(timeZone);
        }
    }

    static boolean validZone(String zone) {
        if (zone == null) {
            return true;
        }
        try {
            ZoneId.of(zone);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
