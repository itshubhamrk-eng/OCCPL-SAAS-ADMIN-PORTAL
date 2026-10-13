package com.occpl.platform.identity.internal;

import com.occpl.platform.access.api.PlatformAccess;
import com.occpl.platform.access.api.PlatformRoleChange;
import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.shared.error.ApiException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assigns platform roles to users. Authorization is enforced by the controller. */
@Service
public class PlatformUserRoleService {

    private final PlatformUserRepository users;
    private final PlatformAccess platformAccess;
    private final AuditLogger audit;
    private final ApplicationEventPublisher events;

    PlatformUserRoleService(PlatformUserRepository users, PlatformAccess platformAccess, AuditLogger audit,
            ApplicationEventPublisher events) {
        this.users = users;
        this.platformAccess = platformAccess;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Set<String> roles(UUID userId) {
        requireUser(userId);
        return platformAccess.roleCodes(userId);
    }

    @Transactional
    public PlatformRoleChange replaceRoles(UUID userId, Set<String> roleCodes) {
        requireUser(userId);
        PlatformRoleChange change = platformAccess.replaceRoles(userId, roleCodes, CurrentUser.require().userId());
        if (change.changed()) {
            audit.record(AuditEvent.success("platform.user.roles_changed", "user", userId, null,
                    Map.of("added", change.added(), "removed", change.removed())));
        }
        if (!change.added().isEmpty()) {
            events.publishEvent(new PlatformRolesGranted(userId, change.added()));
        }
        return change;
    }

    private void requireUser(UUID userId) {
        if (!users.existsById(userId)) {
            throw ApiException.notFound("User");
        }
    }
}
