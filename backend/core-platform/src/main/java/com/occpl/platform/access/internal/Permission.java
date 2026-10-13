package com.occpl.platform.access.internal;

import com.occpl.platform.access.api.PermissionScope;
import com.occpl.platform.access.api.PermissionView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only catalogue entry; rows are maintained by the seed migration. */
@Entity
@Table(name = "permission")
class Permission {

    @Id
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PermissionScope scope;

    @Column(nullable = false)
    private String description;

    protected Permission() {
    }

    PermissionView toView() {
        return new PermissionView(code, scope, description);
    }
}
