package com.occpl.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.occpl.platform.support.MySqlTestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Boots the full application against a real MySQL to verify Flyway, JPA validation and probes. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0", "management.server.port=0"})
class CorePlatformApplicationIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    Environment environment;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayHasInitialisedTheSchema() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = 1", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(6);
    }

    @Test
    void auditLogIsAppendOnly() {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.update("insert into audit_log (id, occurred_at, actor_type, action, target_type, outcome) "
                + "values (UUID_TO_BIN(?), UTC_TIMESTAMP(6), 'SYSTEM', 'test.append_only', 'test', 'SUCCESS')", id);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "update audit_log set action = 'tampered' where id = UUID_TO_BIN(?)", id))
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "delete from audit_log where id = UUID_TO_BIN(?)", id))
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("select action from audit_log where id = UUID_TO_BIN(?)", String.class, id))
                .isEqualTo("test.append_only");
    }

    @Test
    void seedsPermissionCatalogueAndSystemRoles() {
        assertThat(jdbc.queryForObject("select count(*) from permission", Integer.class)).isEqualTo(28);
        assertThat(jdbc.queryForList("select code from role where system_role order by code", String.class))
                .containsExactly("COMPANY_ADMIN", "COMPANY_MANAGER", "COMPANY_USER", "PLATFORM_SUPPORT", "SUPER_ADMIN");
        assertThat(jdbc.queryForObject("select count(*) from role_permission rp join role r on r.id = rp.role_id "
                + "where r.code = 'PLATFORM_SUPPORT' and rp.permission_code not like '%.read'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from role_permission rp join role r on r.id = rp.role_id "
                + "join permission p on p.code = rp.permission_code where r.scope <> p.scope", Integer.class))
                .as("roles only contain permissions of their own scope").isZero();
    }

    @Test
    void exposesLivenessAndReadinessOnManagementPort() throws Exception {
        assertThat(managementGet("/actuator/health/liveness").body()).contains("\"UP\"");
        assertThat(managementGet("/actuator/health/readiness").body()).contains("\"UP\"");
    }

    @Test
    void exposesPrometheusMetricsIncludingConnectionPool() throws Exception {
        HttpResponse<String> response = managementGet("/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hikaricp_connections_active");
    }

    @Test
    void deniesNonWhitelistedActuatorEndpoints() throws Exception {
        assertThat(managementGet("/actuator/env").statusCode()).isIn(401, 403, 404);
    }

    @Test
    void apiDocsAreDisabledByDefault() throws Exception {
        HttpResponse<String> response = get(serverPort(), "/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> managementGet(String path) throws Exception {
        return get(environment.getRequiredProperty("local.management.port", Integer.class), path);
    }

    private int serverPort() {
        return environment.getRequiredProperty("local.server.port", Integer.class);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
