package com.occpl.platform.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * One Keycloak 26.8.1 container (project realm import) shared by all Keycloak integration tests,
 * plus admin helpers. Host port 3025 is exposed to the container so Keycloak can send email to an
 * in-process SMTP server (GreenMail) at {@code host.testcontainers.internal}.
 */
public final class KeycloakTestServer {

    public static final String ADMIN_CLIENT_SECRET = "integration-test-secret";
    public static final String CORE_SERVICES_CLIENT_SECRET = "core-services-test-secret";
    public static final String SAMPLE_SERVICE_CLIENT_SECRET = "sample-service-test-secret";
    public static final String PASSWORD = "Integration-Test-Passw0rd!";
    public static final int SMTP_PORT = 3025;

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("resource")
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("keycloak/keycloak:26.8.1")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withEnv("KEYCLOAK_ADMIN_CLIENT_SECRET", ADMIN_CLIENT_SECRET)
            .withEnv("PROVISIONING_CLIENT_SECRET", CORE_SERVICES_CLIENT_SECRET)
            .withEnv("PRODUCT_SAMPLE_CLIENT_SECRET", SAMPLE_SERVICE_CLIENT_SECRET)
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../../infra/docker/keycloak/occpl-realm.json")),
                    "/opt/keycloak/data/import/occpl-realm.json")
            .withAccessToHost(true)
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        Testcontainers.exposeHostPorts(SMTP_PORT);
        KEYCLOAK.start();
        try {
            // Password-grant clients exist only for tests; the realm itself allows no password grant.
            createClient(Map.of("clientId", "it-direct", "publicClient", true, "directAccessGrantsEnabled", true,
                    "standardFlowEnabled", false, "protocolMappers", List.of(Map.of("name", "aud",
                            "protocol", "openid-connect", "protocolMapper", "oidc-audience-mapper",
                            "config", Map.of("included.custom.audience", "occpl-core", "access.token.claim", "true")))));
            createClient(Map.of("clientId", "it-noaud", "publicClient", true, "directAccessGrantsEnabled", true,
                    "standardFlowEnabled", false));
            send("PUT", baseUrl() + "/admin/realms/occpl", masterAdminToken(), JSON.writeValueAsString(Map.of(
                    "smtpServer", Map.of("host", "host.testcontainers.internal", "port", String.valueOf(SMTP_PORT),
                            "from", "no-reply@occpl.test", "fromDisplayName", "OCCPL Sign-in"))));
        } catch (Exception e) {
            throw new IllegalStateException("Keycloak test setup failed", e);
        }
    }

    private KeycloakTestServer() {
    }

    public static String baseUrl() {
        return "http://localhost:" + KEYCLOAK.getMappedPort(8080);
    }

    public static String realmUrl() {
        return baseUrl() + "/realms/occpl";
    }

    /** Marks an existing Keycloak user's email verified and sets the test password. */
    public static void makeReady(String subject) throws Exception {
        String admin = masterAdminToken();
        send("PUT", baseUrl() + "/admin/realms/occpl/users/" + subject, admin,
                "{\"emailVerified\":true,\"requiredActions\":[]}");
        send("PUT", baseUrl() + "/admin/realms/occpl/users/" + subject + "/reset-password", admin,
                "{\"type\":\"password\",\"value\":\"" + PASSWORD + "\",\"temporary\":false}");
    }

    public static String passwordGrant(String clientId, String username, String password) throws Exception {
        return token(realmUrl(), "grant_type=password&client_id=" + clientId + "&username="
                + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password="
                + URLEncoder.encode(password, StandardCharsets.UTF_8));
    }

    public static String passwordGrant(String clientId, String username) throws Exception {
        return passwordGrant(clientId, username, PASSWORD);
    }

    public static String masterAdminToken() throws Exception {
        return token(baseUrl() + "/realms/master", "grant_type=password&client_id=admin-cli&username=admin&password=admin");
    }

    public static HttpResponse<String> send(String method, String url, String bearer, String json) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode() / 100).as(method + " " + url + ": " + response.body()).isEqualTo(2);
        return response;
    }

    private static String token(String realm, String form) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(realm + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("access_token").asString();
    }

    public static void createClient(Map<String, Object> client) throws Exception {
        send("POST", baseUrl() + "/admin/realms/occpl/clients", masterAdminToken(), JSON.writeValueAsString(client));
    }
}
