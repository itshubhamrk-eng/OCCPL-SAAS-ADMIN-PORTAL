package com.occpl.platform.identity.web;

import com.occpl.platform.identity.api.UserStatus;
import com.occpl.platform.identity.internal.PlatformUserService;
import com.occpl.platform.shared.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
class PlatformUserController {

    private final PlatformUserService users;

    PlatformUserController(PlatformUserService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.users.read')")
    @Operation(summary = "List platform users (keyset pagination)",
            description = "Pass the previous response's nextCursor as 'after'. q matches the start of the email.")
    PlatformUserService.UserPage list(@RequestParam(required = false) UUID after,
            @RequestParam(required = false) UserStatus status, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int size) {
        if (size < 1 || size > 100) {
            throw ApiException.validation("size must be between 1 and 100.");
        }
        return users.list(after, status, q, size);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('platform.users.read')")
    PlatformUserService.UserView get(@PathVariable UUID userId) {
        return users.get(userId);
    }

    @PostMapping("/{userId}/disable")
    @PreAuthorize("hasAuthority('platform.users.manage')")
    @Operation(summary = "Disable a user platform-wide", description = "Also disables the Keycloak account and ends its sessions.")
    PlatformUserService.UserView disable(@PathVariable UUID userId,
            @Valid @RequestBody(required = false) ReasonRequest request) {
        return users.disable(userId, request == null ? null : request.reason());
    }

    @PostMapping("/{userId}/enable")
    @PreAuthorize("hasAuthority('platform.users.manage')")
    PlatformUserService.UserView enable(@PathVariable UUID userId) {
        return users.enable(userId);
    }

    record ReasonRequest(@Size(max = 500) String reason) {
    }
}
