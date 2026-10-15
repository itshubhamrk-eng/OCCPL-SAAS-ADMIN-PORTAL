package com.occpl.platform.access.internal;

import com.occpl.platform.access.api.PermissionScope;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RoleRepository extends JpaRepository<Role, UUID> {

    @EntityGraph(attributePaths = "permissions")
    @Query("select r from Role r where r.scope = :scope and r.companyId is null order by r.code")
    List<Role> findGlobalByScope(PermissionScope scope);

    @Query("select r from Role r where r.scope = 'PLATFORM' and r.code in :codes")
    List<Role> findPlatformRolesByCode(Collection<String> codes);

    @EntityGraph(attributePaths = "permissions")
    @Query("""
            select r from Role r where r.scope = 'COMPANY' and (r.companyId is null or r.companyId = :companyId)
            order by r.systemRole desc, r.code""")
    List<Role> findAssignable(UUID companyId);

    @Query("select r from Role r where r.scope = 'PLATFORM' and r.code = :code")
    Optional<Role> findPlatformRole(String code);
}
