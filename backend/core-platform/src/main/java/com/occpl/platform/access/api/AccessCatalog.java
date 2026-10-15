package com.occpl.platform.access.api;

import java.util.List;

public interface AccessCatalog {

    List<PermissionView> permissions(PermissionScope scope);

    List<RoleView> platformRoles();
}
