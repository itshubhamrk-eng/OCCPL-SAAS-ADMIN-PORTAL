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
@Table(name = "membership_role")
@IdClass(MembershipRoleAssignment.Key.class)
class MembershipRoleAssignment {

    @Id
    @Column(name = "membership_id")
    private UUID membershipId;

    @Id
    @Column(name = "role_id")
    private UUID roleId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    protected MembershipRoleAssignment() {
    }

    MembershipRoleAssignment(UUID membershipId, UUID roleId, Instant assignedAt, UUID assignedBy) {
        this.membershipId = membershipId;
        this.roleId = roleId;
        this.assignedAt = assignedAt;
        this.assignedBy = assignedBy;
    }

    record Key(UUID membershipId, UUID roleId) implements Serializable {

        @SuppressWarnings("unused") // required by JPA
        Key() {
            this(null, null);
        }
    }
}
