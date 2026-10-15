package com.occpl.platform.access.internal;

import com.occpl.platform.access.api.PermissionScope;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PermissionRepository extends JpaRepository<Permission, String> {

    List<Permission> findByScopeOrderByCode(PermissionScope scope);
}
