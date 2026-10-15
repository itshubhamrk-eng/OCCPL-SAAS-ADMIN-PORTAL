package com.occpl.platform.identity.internal;

import com.occpl.platform.access.api.PlatformAccess;
import com.occpl.platform.access.api.PlatformRoles;
import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.identity.api.UserStatus;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.paging.Likes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-wide user administration (OCCPL staff). Authorization is enforced by the controller. */
@Service
public class PlatformUserService {

    private final PlatformUserRepository users;
    private final UserProfileRepository profiles;
    private final UserIdentityRepository identities;
    private final PlatformAccess platformAccess;
    private final AuditLogger audit;
    private final ApplicationEventPublisher events;
    private final IdentityProperties properties;
    private final Clock clock;

    PlatformUserService(PlatformUserRepository users, UserProfileRepository profiles, UserIdentityRepository identities,
            PlatformAccess platformAccess, AuditLogger audit, ApplicationEventPublisher events,
            IdentityProperties properties, Clock clock) {
        this.users = users;
        this.profiles = profiles;
        this.identities = identities;
        this.platformAccess = platformAccess;
        this.audit = audit;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    public record UserView(UUID id, String email, String displayName, UserStatus status, Instant lastLoginAt,
            Instant createdAt, Set<String> platformRoles) {
    }

    public record UserPage(List<UserView> items, UUID nextCursor) {
    }

    @Transactional(readOnly = true)
    public UserPage list(UUID after, UserStatus status, String q, int size) {
        List<PlatformUser> page = users.page(after, status, Likes.prefix(q == null ? null : q.toLowerCase()),
                Limit.of(size + 1));
        boolean more = page.size() > size;
        List<PlatformUser> items = more ? page.subList(0, size) : page;
        Map<UUID, String> names = profiles.findAllById(items.stream().map(PlatformUser::id).toList()).stream()
                .filter(p -> p.displayName() != null)
                .collect(Collectors.toMap(UserProfile::userId, UserProfile::displayName));
        List<UserView> views = items.stream().map(u -> new UserView(u.id(), u.email(), names.get(u.id()), u.status(),
                u.lastLoginAt(), u.createdAt(), null)).toList();
        return new UserPage(views, more ? items.getLast().id() : null);
    }

    @Transactional(readOnly = true)
    public UserView get(UUID userId) {
        PlatformUser user = require(userId);
        return new UserView(user.id(), user.email(),
                profiles.findById(userId).map(UserProfile::displayName).orElse(null), user.status(),
                user.lastLoginAt(), user.createdAt(), new TreeSet<>(platformAccess.roleCodes(userId)));
    }

    /**
     * Disables the user everywhere: Core rejects their tokens immediately and, after commit, the
     * Keycloak account is disabled and its sessions ended.
     */
    @Transactional
    public UserView disable(UUID userId, String reason) {
        CurrentUser actor = CurrentUser.require();
        if (actor.userId().equals(userId)) {
            throw new ApiException(ErrorCode.CONFLICT, "You cannot disable your own account.");
        }
        PlatformUser user = require(userId);
        if (user.status() != UserStatus.DISABLED) {
            if (platformAccess.roleCodes(userId).contains(PlatformRoles.SUPER_ADMIN)) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Remove the SUPER_ADMIN role before disabling this user.");
            }
            user.disable(clock.instant());
            audit.record(AuditEvent.success("platform.user.disabled", "user", userId, null,
                    reason == null ? Map.of() : Map.of("reason", reason)));
            events.publishEvent(new UserAccessChanged(userId, false));
        }
        return get(userId);
    }

    @Transactional
    public UserView enable(UUID userId) {
        PlatformUser user = require(userId);
        if (user.status() == UserStatus.DISABLED) {
            user.enable(identities.findByUserIdAndProvider(userId, properties.providerId()).isPresent(),
                    clock.instant());
            audit.record(AuditEvent.success("platform.user.enabled", "user", userId, null, Map.of()));
            events.publishEvent(new UserAccessChanged(userId, true));
        }
        return get(userId);
    }

    private PlatformUser require(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }
}
