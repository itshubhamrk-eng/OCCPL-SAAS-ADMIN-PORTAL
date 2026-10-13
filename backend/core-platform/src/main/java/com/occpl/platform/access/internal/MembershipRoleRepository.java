package com.occpl.platform.access.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;

interface MembershipRoleRepository extends Repository<Role, UUID> {

    /** Rows of (membershipId, roleCode, permissionCode) for the given memberships. */
    @Query(value = """
            select BIN_TO_UUID(mr.membership_id) as membershipId, r.code as roleCode, rp.permission_code as permissionCode
            from membership_role mr
            join role r on r.id = mr.role_id
            left join role_permission rp on rp.role_id = r.id
            where mr.membership_id in (:membershipIds)""", nativeQuery = true)
    List<MembershipRoleRow> findAccessRows(Collection<UUID> membershipIds);

    interface MembershipRoleRow {
        String getMembershipId();

        String getRoleCode();

        String getPermissionCode();
    }
}
