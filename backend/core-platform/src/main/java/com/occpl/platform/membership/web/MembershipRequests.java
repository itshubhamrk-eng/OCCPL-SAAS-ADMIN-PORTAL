package com.occpl.platform.membership.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

final class MembershipRequests {

    private MembershipRequests() {
    }

    record Invite(@NotBlank @Email @Size(max = 320) String email,
            @NotEmpty @Size(max = 10) Set<@NotNull @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String> roles,
            @Size(max = 100) String firstName, @Size(max = 100) String lastName) {
    }

    record Accept(@NotBlank @Size(max = 100) String token) {
    }

    record Roles(@NotEmpty @Size(max = 10) Set<@NotNull @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String> roles) {
    }

    record Reason(@Size(max = 500) String reason) {
    }
}
