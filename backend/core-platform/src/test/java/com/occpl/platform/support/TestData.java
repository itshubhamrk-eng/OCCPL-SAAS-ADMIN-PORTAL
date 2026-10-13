package com.occpl.platform.support;

import com.occpl.platform.shared.id.UuidV7;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Inserts rows directly for scenarios whose management APIs arrive in later phases. */
public final class TestData {

    public static final UUID COMPANY_ADMIN_ROLE = UUID.fromString("01920000-0000-7000-8000-000000000011");
    public static final UUID COMPANY_MANAGER_ROLE = UUID.fromString("01920000-0000-7000-8000-000000000012");
    public static final UUID COMPANY_USER_ROLE = UUID.fromString("01920000-0000-7000-8000-000000000013");
    public static final UUID SUPER_ADMIN_ROLE = UUID.fromString("01920000-0000-7000-8000-000000000001");

    private final JdbcTemplate jdbc;

    public TestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID company(String code, String status) {
        UUID id = UuidV7.generate();
        jdbc.update("""
                insert into company (id, code, legal_name, display_name, status, country, created_at, updated_at)
                values (UUID_TO_BIN(?), ?, ?, ?, ?, 'IN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                id.toString(), code, code + " Pvt Ltd", code, status);
        return id;
    }

    public UUID user(String email, String status) {
        UUID id = UuidV7.generate();
        jdbc.update("""
                insert into platform_user (id, email, status, created_at, updated_at)
                values (UUID_TO_BIN(?), ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", id.toString(), email, status);
        return id;
    }

    public void identity(UUID userId, String subject) {
        jdbc.update("""
                insert into user_identity (id, user_id, provider, subject, created_at)
                values (UUID_TO_BIN(?), UUID_TO_BIN(?), 'keycloak', ?, UTC_TIMESTAMP(6))""",
                UuidV7.generate().toString(), userId.toString(), subject);
    }

    public UUID membership(UUID companyId, UUID userId, String status, UUID... roleIds) {
        UUID id = UuidV7.generate();
        jdbc.update("""
                insert into company_membership (id, company_id, user_id, status, created_at, updated_at)
                values (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                id.toString(), companyId.toString(), userId.toString(), status);
        for (UUID roleId : roleIds) {
            jdbc.update("""
                    insert into membership_role (membership_id, role_id, assigned_at)
                    values (UUID_TO_BIN(?), UUID_TO_BIN(?), UTC_TIMESTAMP(6))""", id.toString(), roleId.toString());
        }
        return id;
    }

    public void platformRole(UUID userId, UUID roleId) {
        jdbc.update("""
                insert into platform_role_assignment (user_id, role_id, assigned_at)
                values (UUID_TO_BIN(?), UUID_TO_BIN(?), UTC_TIMESTAMP(6))""", userId.toString(), roleId.toString());
    }

    public UUID userIdByEmail(String email) {
        return UUID.fromString(jdbc.queryForObject(
                "select BIN_TO_UUID(id) from platform_user where email = ?", String.class, email));
    }
}
