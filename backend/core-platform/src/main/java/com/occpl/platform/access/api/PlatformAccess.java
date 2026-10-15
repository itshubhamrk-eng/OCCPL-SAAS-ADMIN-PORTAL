package com.occpl.platform.access.api;

import java.util.Set;
import java.util.UUID;

/** Platform-scope roles of users. Callers are responsible for authorization and auditing. */
public interface PlatformAccess {

    Set<String> roleCodes(UUID userId);

    Set<String> permissions(UUID userId);

    boolean anyUserHasRole(String roleCode);

    /**
     * Replaces the user's platform roles. Rejects unknown or non-platform role codes and refuses to
     * remove the last {@code SUPER_ADMIN}. Must run inside the caller's transaction.
     */
    PlatformRoleChange replaceRoles(UUID userId, Set<String> roleCodes, UUID assignedBy);
}
