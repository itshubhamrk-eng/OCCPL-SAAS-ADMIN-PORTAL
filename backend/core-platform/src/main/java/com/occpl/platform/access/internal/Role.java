package com.occpl.platform.access.internal;

import com.occpl.platform.access.api.PermissionScope;
import com.occpl.platform.access.api.RoleView;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "role")
class Role {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PermissionScope scope;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "system_role", nullable = false)
    private boolean systemRole;

    @Column(nullable = false)
    private String name;

    private String description;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "role_permission", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_code")
    private Set<String> permissions = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Role() {
    }

    UUID id() {
        return id;
    }

    String code() {
        return code;
    }

    PermissionScope scope() {
        return scope;
    }

    RoleView toView() {
        return new RoleView(id, code, scope, systemRole, name, description, Set.copyOf(permissions));
    }
}
