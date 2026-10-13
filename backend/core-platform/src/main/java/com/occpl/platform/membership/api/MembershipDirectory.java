package com.occpl.platform.membership.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read access to memberships for other modules (e.g. product access checks for product services). */
public interface MembershipDirectory {

    /** The user's membership in the company if it is ACTIVE, with the permissions of its roles. */
    Optional<ActiveMember> activeMember(UUID companyId, UUID userId);

    record ActiveMember(UUID membershipId, Set<String> roleCodes, Set<String> permissions) {

        public ActiveMember {
            roleCodes = Set.copyOf(roleCodes);
            permissions = Set.copyOf(permissions);
        }
    }
}
