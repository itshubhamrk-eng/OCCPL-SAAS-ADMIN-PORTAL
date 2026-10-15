package com.occpl.platform.access.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface MembershipRoleAssignmentRepository
        extends JpaRepository<MembershipRoleAssignment, MembershipRoleAssignment.Key> {

    @Modifying
    @Query("delete from MembershipRoleAssignment a where a.membershipId = :membershipId")
    void deleteByMembershipId(UUID membershipId);
}
