package com.occpl.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Boots the full application against a real MySQL to verify Flyway, JPA validation and probes. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0", "management.server.port=0"})
class CorePlatformApplicationIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7");

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    Environment environment;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayHasInitialisedTheSchema() {
        Integer historyTables = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'flyway_schema_history'",
                Integer.class);
        assertThat(historyTables).isEqualTo(1);
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
