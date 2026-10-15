package com.occpl.platform.access.api;

import java.util.Set;
import java.util.UUID;

public record RoleView(UUID id, String code, PermissionScope scope, boolean systemRole, String name, String description,
        Set<String> permissions) {
}
