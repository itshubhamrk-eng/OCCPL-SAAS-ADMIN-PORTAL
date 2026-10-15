package com.occpl.platform.identity.internal;

import com.occpl.platform.access.api.PlatformAccess;
import com.occpl.platform.access.api.PlatformRoles;
import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.identity.api.UserStatus;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.id.UuidV7;
import com.occpl.platform.shared.security.AccountAccessException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Maps a validated IdP token to a platform user (SECURITY.md §1, BACKLOG I-2):
 * <ul>
 *   <li>known subject → its user (rejected if disabled);</li>
 *   <li>unknown subject with a <b>verified</b> email → linked to the existing (e.g. invited) user with
 *       that email, or a new user is created;</li>
 *   <li>an email already linked to a different subject of the same provider is never re-linked, so a
 *       recreated or impersonating IdP account cannot take over an existing user.</li>
 * </ul>
 */
@Service
class UserProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioningService.class);

    private final PlatformUserRepository users;
    private final UserIdentityRepository identities;
    private final UserProfileRepository profiles;
    private final PlatformAccess platformAccess;
    private final AuditLogger audit;
    private final ApplicationEventPublisher events;
    private final IdentityProperties properties;
    private final Clock clock;

    UserProvisioningService(PlatformUserRepository users, UserIdentityRepository identities,
            UserProfileRepository profiles, PlatformAccess platformAccess, AuditLogger audit,
            ApplicationEventPublisher events, IdentityProperties properties, Clock clock) {
        this.users = users;
        this.identities = identities;
        this.profiles = profiles;
        this.platformAccess = platformAccess;
        this.audit = audit;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    record ResolvedUser(UUID id, String email, String displayName) {
    }

    @Transactional
    public ResolvedUser resolve(Jwt jwt) {
        Instant now = clock.instant();
        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw new AccountAccessException(ErrorCode.IDENTITY_CONFLICT, "The token has no subject.");
        }
        PlatformUser user = identities.findByProviderAndSubject(properties.providerId(), subject)
                .map(identity -> users.findById(identity.userId()).orElseThrow())
                .orElseGet(() -> linkOrCreate(jwt, subject, now));

        if (user.status() == UserStatus.DISABLED) {
            throw new AccountAccessException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled.");
        }
        syncEmail(user, jwt, now);
        user.recordLogin(now);
        maybeBootstrapSuperAdmin(user);

        String displayName = profiles.findById(user.id()).map(UserProfile::displayName).orElse(null);
        return new ResolvedUser(user.id(), user.email(), displayName);
    }

    private PlatformUser linkOrCreate(Jwt jwt, String subject, Instant now) {
        String email = verifiedEmail(jwt);
        if (email == null) {
            throw new AccountAccessException(ErrorCode.EMAIL_NOT_VERIFIED,
                    "Verify your email address before signing in.");
        }
        PlatformUser user = users.findByEmail(email).orElse(null);
        if (user == null) {
            user = users.save(new PlatformUser(UuidV7.generate(clock), email, UserStatus.ACTIVE, now));
            profiles.save(new UserProfile(user.id(), displayName(jwt), now));
            log.info("Created platform user {} on first login", user.id());
        } else {
            if (identities.findByUserIdAndProvider(user.id(), properties.providerId()).isPresent()) {
                log.warn("Refused to link subject to user {}: user already linked to another subject", user.id());
                throw new AccountAccessException(ErrorCode.IDENTITY_CONFLICT,
                        "This email address is already linked to another sign-in account. Contact support.");
            }
            user.activate(now);
            if (!profiles.existsById(user.id())) {
                profiles.save(new UserProfile(user.id(), displayName(jwt), now));
            }
            log.info("Linked identity to existing platform user {}", user.id());
        }
        identities.save(new UserIdentity(UuidV7.generate(clock), user.id(), properties.providerId(), subject, now));
        return user;
    }

    /** Keeps the stored email in line with the IdP when the new address is verified and not taken. */
    private void syncEmail(PlatformUser user, Jwt jwt, Instant now) {
        String email = verifiedEmail(jwt);
        if (email != null && !email.equals(user.email())) {
            if (users.existsByEmail(email)) {
                log.warn("Not updating email of user {}: address belongs to another user", user.id());
            } else {
                user.changeEmail(email, now);
            }
        }
    }

    private void maybeBootstrapSuperAdmin(PlatformUser user) {
        String bootstrapEmail = properties.bootstrapSuperAdminEmail();
        if (!StringUtils.hasText(bootstrapEmail)
                || !PlatformUser.normalizeEmail(bootstrapEmail).equals(user.email())
                || platformAccess.anyUserHasRole(PlatformRoles.SUPER_ADMIN)) {
            return;
        }
        Set<String> roles = new HashSet<>(platformAccess.roleCodes(user.id()));
        roles.add(PlatformRoles.SUPER_ADMIN);
        platformAccess.replaceRoles(user.id(), roles, null);
        audit.recordSystem(AuditEvent.success("platform.super_admin.bootstrapped", "user", user.id(), null,
                Map.of("roles", Set.of(PlatformRoles.SUPER_ADMIN))));
        events.publishEvent(new PlatformRolesGranted(user.id(), Set.of(PlatformRoles.SUPER_ADMIN)));
        log.warn("Bootstrapped first SUPER_ADMIN: user {}. Remove occpl.identity.bootstrap-super-admin-email.",
                user.id());
    }

    private static String verifiedEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        Boolean verified = jwt.getClaimAsBoolean("email_verified");
        if (!StringUtils.hasText(email) || !Boolean.TRUE.equals(verified)) {
            return null;
        }
        return PlatformUser.normalizeEmail(email);
    }

    private static String displayName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (!StringUtils.hasText(name)) {
            return null;
        }
        return name.length() > 200 ? name.substring(0, 200) : name;
    }
}
