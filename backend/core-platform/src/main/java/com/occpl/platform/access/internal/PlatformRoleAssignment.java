package com.occpl.platform.access.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "platform_role_assignment")
@IdClass(PlatformRoleAssignment.Key.class)
class PlatformRoleAssignment {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "role_id")
    private UUID roleId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    protected PlatformRoleAssignment() {
    }

    PlatformRoleAssignment(UUID userId, UUID roleId, Instant assignedAt, UUID assignedBy) {
        this.userId = userId;
        this.roleId = roleId;
        this.assignedAt = assignedAt;
        this.assignedBy = assignedBy;
    }

    UUID userId() {
        return userId;
    }

    UUID roleId() {
        return roleId;
    }

    record Key(UUID userId, UUID roleId) implements Serializable {

        @SuppressWarnings("unused") // required by JPA
        Key() {
            this(null, null);
        }
    }
}
