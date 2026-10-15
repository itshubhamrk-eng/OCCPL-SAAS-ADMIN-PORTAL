package com.occpl.platform.access.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Company-scope roles and permissions of memberships. */
public interface CompanyAccess {

    /** Batch lookup to avoid N+1 queries; memberships without roles map to {@link MembershipAccess#NONE}. */
    Map<UUID, MembershipAccess> forMemberships(Collection<UUID> membershipIds);

    /** Roles assignable in the company (system company roles plus the company's custom roles). */
    List<RoleView> assignableRoles(UUID companyId);

    /** Union of the permissions of the given roles; rejects codes that are not assignable in the company. */
    Set<String> permissionsOf(UUID companyId, Set<String> roleCodes);

    /** Replaces a membership's roles (validated as in {@link #permissionsOf}). Must run in the caller's transaction. */
    MembershipAccess replaceMembershipRoles(UUID companyId, UUID membershipId, Set<String> roleCodes, UUID assignedBy);
}
