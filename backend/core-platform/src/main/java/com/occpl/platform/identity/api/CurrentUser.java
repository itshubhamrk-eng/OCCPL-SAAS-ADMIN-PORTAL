package com.occpl.platform.identity.api;

import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.security.Actor;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The authenticated platform user for the current request.
 *
 * @param platformRoles platform roles assigned to the user
 * @param platformPermissions platform permissions actually granted for this request (empty while
 *        MFA is required but was not used for this login)
 * @param platformMfaRequired true when the user holds platform roles but authenticated without MFA
 */
public record CurrentUser(UUID userId, String email, String displayName, Set<String> platformRoles,
        Set<String> platformPermissions, boolean platformMfaRequired) implements Actor {

    public CurrentUser {
        platformRoles = Set.copyOf(platformRoles);
        platformPermissions = Set.copyOf(platformPermissions);
    }

    public boolean hasPlatformPermission(String permission) {
        return platformPermissions.contains(permission);
    }

    public static CurrentUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required.");
    }
}
