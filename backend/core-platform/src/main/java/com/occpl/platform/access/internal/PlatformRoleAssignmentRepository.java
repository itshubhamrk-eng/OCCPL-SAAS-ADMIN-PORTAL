package com.occpl.platform.access.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface PlatformRoleAssignmentRepository
        extends JpaRepository<PlatformRoleAssignment, PlatformRoleAssignment.Key> {

    List<PlatformRoleAssignment> findByUserId(UUID userId);

    boolean existsByRoleId(UUID roleId);

    /** Locks every assignment of a role, serialising concurrent "remove last holder" attempts. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PlatformRoleAssignment a where a.roleId = :roleId")
    List<PlatformRoleAssignment> lockAllByRoleId(UUID roleId);

    @Query("""
            select r.code from PlatformRoleAssignment a, Role r
            where r.id = a.roleId and a.userId = :userId""")
    Set<String> findRoleCodes(UUID userId);

    @Query("""
            select distinct p from PlatformRoleAssignment a, Role r join r.permissions p
            where r.id = a.roleId and a.userId = :userId""")
    Set<String> findPermissions(UUID userId);
}
