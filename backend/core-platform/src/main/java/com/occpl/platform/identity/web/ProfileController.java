package com.occpl.platform.identity.web;

import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.identity.internal.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/profile")
class ProfileController {

    private final ProfileService profiles;

    ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @Operation(summary = "The current user's profile")
    ProfileService.ProfileView get() {
        return profiles.get(CurrentUser.require().userId());
    }

    @PutMapping
    @Operation(summary = "Replace the current user's profile")
    ProfileService.ProfileView update(@Valid @RequestBody ProfileRequest request) {
        return profiles.update(CurrentUser.require().userId(), request.displayName().strip(), request.phone(),
                request.locale(), request.timeZone());
    }

    record ProfileRequest(
            @NotBlank @Size(max = 200) String displayName,
            @Pattern(regexp = "\\+[1-9][0-9]{6,14}", message = "must be in international format, e.g. +919876543210")
            String phone,
            @Pattern(regexp = "[a-z]{2,3}(-[A-Z]{2})?", message = "must be a language tag such as en or en-IN")
            String locale,
            @Size(max = 64) String timeZone) {

        @AssertTrue(message = "must be a valid IANA time zone such as Asia/Kolkata")
        boolean isTimeZoneValid() {
            if (timeZone == null) {
                return true;
            }
            try {
                ZoneId.of(timeZone);
                return true;
            } catch (DateTimeException e) {
                return false;
            }
        }
    }
}
