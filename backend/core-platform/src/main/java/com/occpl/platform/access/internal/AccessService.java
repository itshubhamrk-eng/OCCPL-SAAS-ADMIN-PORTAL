package com.occpl.platform.access.internal;

import com.occpl.platform.access.api.AccessCatalog;
import com.occpl.platform.access.api.CompanyAccess;
import com.occpl.platform.access.api.MembershipAccess;
import com.occpl.platform.access.api.PermissionScope;
import com.occpl.platform.access.api.PermissionView;
import com.occpl.platform.access.api.PlatformAccess;
import com.occpl.platform.access.api.PlatformRoleChange;
import com.occpl.platform.access.api.PlatformRoles;
import com.occpl.platform.access.api.RoleView;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class AccessService implements AccessCatalog, PlatformAccess, CompanyAccess {

    private final PermissionRepository permissions;
    private final RoleRepository roles;
    private final PlatformRoleAssignmentRepository platformAssignments;
    private final MembershipRoleRepository membershipRoles;
    private final MembershipRoleAssignmentRepository membershipRoleAssignments;
    private final Clock clock;

    AccessService(PermissionRepository permissions, RoleRepository roles,
            PlatformRoleAssignmentRepository platformAssignments, MembershipRoleRepository membershipRoles,
            MembershipRoleAssignmentRepository membershipRoleAssignments, Clock clock) {
        this.permissions = permissions;
        this.roles = roles;
        this.platformAssignments = platformAssignments;
        this.membershipRoles = membershipRoles;
        this.membershipRoleAssignments = membershipRoleAssignments;
        this.clock = clock;
    }

    @Override
    public List<PermissionView> permissions(PermissionScope scope) {
        return permissions.findByScopeOrderByCode(scope).stream().map(Permission::toView).toList();
    }

    @Override
    public List<RoleView> platformRoles() {
        return roles.findGlobalByScope(PermissionScope.PLATFORM).stream().map(Role::toView).toList();
    }

    @Override
    public Set<String> roleCodes(UUID userId) {
        return Set.copyOf(platformAssignments.findRoleCodes(userId));
    }

    @Override
    public Set<String> permissions(UUID userId) {
        return Set.copyOf(platformAssignments.findPermissions(userId));
    }

    @Override
    public boolean anyUserHasRole(String roleCode) {
        return roles.findPlatformRole(roleCode).map(r -> platformAssignments.existsByRoleId(r.id())).orElse(false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PlatformRoleChange replaceRoles(UUID userId, Set<String> roleCodes, UUID assignedBy) {
        Map<String, Role> requested = roles.findPlatformRolesByCode(roleCodes).stream()
                .collect(Collectors.toMap(Role::code, Function.identity()));
        Set<String> unknown = new TreeSet<>(roleCodes);
        unknown.removeAll(requested.keySet());
        if (!unknown.isEmpty()) {
            throw ApiException.validation("Unknown platform role(s): " + String.join(", ", unknown) + ".");
        }

        Map<UUID, PlatformRoleAssignment> existing = platformAssignments.findByUserId(userId).stream()
                .collect(Collectors.toMap(PlatformRoleAssignment::roleId, Function.identity()));
        Set<String> currentCodes = platformAssignments.findRoleCodes(userId);

        Set<String> removed = new TreeSet<>(currentCodes);
        removed.removeAll(roleCodes);
        Set<String> added = new TreeSet<>(roleCodes);
        added.removeAll(currentCodes);

        if (removed.contains(PlatformRoles.SUPER_ADMIN)) {
            Role superAdmin = roles.findPlatformRole(PlatformRoles.SUPER_ADMIN).orElseThrow();
            if (platformAssignments.lockAllByRoleId(superAdmin.id()).size() <= 1) {
                throw new ApiException(ErrorCode.LAST_ADMIN, "The platform must keep at least one Super Admin.");
            }
        }

        for (String code : removed) {
            roles.findPlatformRole(code).map(Role::id).map(existing::get).ifPresent(platformAssignments::delete);
        }
        for (String code : added) {
            platformAssignments.save(
                    new PlatformRoleAssignment(userId, requested.get(code).id(), clock.instant(), assignedBy));
        }
        return new PlatformRoleChange(added, removed, new TreeSet<>(roleCodes));
    }

    @Override
    public List<RoleView> assignableRoles(UUID companyId) {
        return roles.findAssignable(companyId).stream().map(Role::toView).toList();
    }

    @Override
    public Set<String> permissionsOf(UUID companyId, Set<String> roleCodes) {
        return resolveAssignable(companyId, roleCodes).values().stream()
                .flatMap(r -> r.toView().permissions().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MembershipAccess replaceMembershipRoles(UUID companyId, UUID membershipId, Set<String> roleCodes,
            UUID assignedBy) {
        Map<String, Role> requested = resolveAssignable(companyId, roleCodes);
        membershipRoleAssignments.deleteByMembershipId(membershipId);
        membershipRoleAssignments.flush();
        for (Role role : requested.values()) {
            membershipRoleAssignments.save(new MembershipRoleAssignment(membershipId, role.id(), clock.instant(),
                    assignedBy));
        }
        return new MembershipAccess(Set.copyOf(requested.keySet()), permissionsOf(companyId, roleCodes));
    }

    private Map<String, Role> resolveAssignable(UUID companyId, Set<String> roleCodes) {
        Map<String, Role> assignable = roles.findAssignable(companyId).stream()
                .collect(Collectors.toMap(Role::code, Function.identity()));
        Set<String> unknown = new TreeSet<>(roleCodes);
        unknown.removeAll(assignable.keySet());
        if (!unknown.isEmpty()) {
            throw ApiException.validation("Unknown company role(s): " + String.join(", ", unknown) + ".");
        }
        Map<String, Role> result = new HashMap<>();
        roleCodes.forEach(code -> result.put(code, assignable.get(code)));
        return result;
    }

    @Override
    public Map<UUID, MembershipAccess> forMemberships(Collection<UUID> membershipIds) {
        if (membershipIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<String>> roleCodes = new HashMap<>();
        Map<UUID, Set<String>> perms = new HashMap<>();
        for (var row : membershipRoles.findAccessRows(Set.copyOf(membershipIds))) {
            UUID membershipId = UUID.fromString(row.getMembershipId());
            roleCodes.computeIfAbsent(membershipId, k -> new HashSet<>()).add(row.getRoleCode());
            if (row.getPermissionCode() != null) {
                perms.computeIfAbsent(membershipId, k -> new HashSet<>()).add(row.getPermissionCode());
            }
        }
        Map<UUID, MembershipAccess> result = new HashMap<>();
        for (UUID id : membershipIds) {
            result.put(id, roleCodes.containsKey(id)
                    ? new MembershipAccess(Set.copyOf(roleCodes.get(id)), Set.copyOf(perms.getOrDefault(id, Set.of())))
                    : MembershipAccess.NONE);
        }
        return result;
    }
}
