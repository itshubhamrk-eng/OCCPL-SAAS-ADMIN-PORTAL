package com.occpl.platform.lead.web;

import com.occpl.platform.lead.api.LeadStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class LeadRequests {

    static final String PHONE = "[0-9+()\\-. ]{5,32}";

    private LeadRequests() {
    }

    record RegisterLead(
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank @Size(max = 200) String contactName,
            @NotBlank @Email @Size(max = 320) String email,
            @Pattern(regexp = "|" + PHONE, message = "digits, spaces and + ( ) - . only; 5-32 characters") String phone,
            @Size(max = 200) String productInterest,
            @Size(max = 100) String source,
            @Size(max = 2000) String notes) {
    }

    record UpdateLead(
            @Size(min = 1, max = 200) String companyName,
            @Size(min = 1, max = 200) String contactName,
            @Email @Size(min = 1, max = 320) String email,
            @Pattern(regexp = "|" + PHONE, message = "digits, spaces and + ( ) - . only; 5-32 characters") String phone,
            @Size(max = 200) String productInterest,
            @Size(max = 100) String source,
            @Size(max = 2000) String notes) {
    }

    record ChangeStatus(@NotNull LeadStatus status, @Size(max = 500) String reason) {
    }
}
