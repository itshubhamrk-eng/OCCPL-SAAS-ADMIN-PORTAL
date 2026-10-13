package com.occpl.platform.identity.web;

import com.occpl.platform.access.api.AccessCatalog;
import com.occpl.platform.access.api.RoleView;
import com.occpl.platform.identity.internal.PlatformUserRoleService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
@PreAuthorize("hasAuthority('platform.roles.manage')")
class PlatformUserRolesController {

    private final PlatformUserRoleService service;
    private final AccessCatalog catalog;

    PlatformUserRolesController(PlatformUserRoleService service, AccessCatalog catalog) {
        this.service = service;
        this.catalog = catalog;
    }

    @GetMapping("/roles")
    @Operation(summary = "Platform roles and their permissions")
    List<RoleView> platformRoles() {
        return catalog.platformRoles();
    }

    @GetMapping("/users/{userId}/roles")
    @Operation(summary = "Platform roles of a user")
    UserRolesResponse roles(@PathVariable UUID userId) {
        return new UserRolesResponse(userId, new TreeSet<>(service.roles(userId)));
    }

    @PutMapping("/users/{userId}/roles")
    @Operation(summary = "Replace the platform roles of a user",
            description = "Refuses to remove the last SUPER_ADMIN. Newly granted users must enrol an authenticator app.")
    UserRolesResponse replace(@PathVariable UUID userId, @Valid @RequestBody ReplaceRolesRequest request) {
        return new UserRolesResponse(userId, service.replaceRoles(userId, request.roles()).current());
    }

    record ReplaceRolesRequest(
            @NotNull @Size(max = 20) Set<@NotNull @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String> roles) {
    }

    record UserRolesResponse(UUID userId, Set<String> roles) {
    }
}
