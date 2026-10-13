package com.occpl.platform.identity.internal;

import com.occpl.platform.access.api.PlatformAccess;
import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.shared.security.JwtPrincipalResolver;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a validated JWT into a {@link CurrentUser}. Authorization data (roles, permissions, status)
 * is read from the database on every request rather than trusted from token claims, so changes take
 * effect immediately instead of at token expiry (SECURITY.md §3).
 */
@Component
class CurrentUserResolver implements JwtPrincipalResolver {

    private final UserProvisioningService provisioning;
    private final PlatformAccess platformAccess;
    private final MfaPolicy mfaPolicy;

    CurrentUserResolver(UserProvisioningService provisioning, PlatformAccess platformAccess,
            MfaPolicy mfaPolicy) {
        this.provisioning = provisioning;
        this.platformAccess = platformAccess;
        this.mfaPolicy = mfaPolicy;
    }

    @Override
    public AbstractAuthenticationToken resolve(Jwt jwt) {
        UserProvisioningService.ResolvedUser user = provisionUser(jwt);
        Set<String> roles = platformAccess.roleCodes(user.id());
        Set<String> permissions = roles.isEmpty() ? Set.of() : platformAccess.permissions(user.id());
        boolean mfaMissing = !permissions.isEmpty() && mfaPolicy.required() && !mfaPolicy.satisfiedBy(jwt);
        Set<String> granted = mfaMissing ? Set.of() : permissions;

        CurrentUser principal = new CurrentUser(user.id(), user.email(), user.displayName(), roles, granted,
                mfaMissing);
        return new CurrentUserAuthentication(jwt, principal,
                granted.stream().map(SimpleGrantedAuthority::new).toList());
    }

    private UserProvisioningService.ResolvedUser provisionUser(Jwt jwt) {
        try {
            return provisioning.resolve(jwt);
        } catch (DataIntegrityViolationException concurrentFirstLogin) {
            // Two parallel first requests raced to create the same user/identity; the winner's rows now exist.
            return provisioning.resolve(jwt);
        }
    }
}
