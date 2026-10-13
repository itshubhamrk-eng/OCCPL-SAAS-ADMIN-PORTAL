package com.occpl.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.occpl.platform.identity.api.IdentityProviderAdmin;
import com.occpl.platform.identity.api.IdentityProviderAdmin.IdpUser;
import com.occpl.platform.identity.api.IdentityProviderAdmin.RequiredAction;
import com.occpl.platform.support.MySqlTestDatabase;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end identity tests against Keycloak 26.8.1 running the project's realm import
 * (infra/docker/keycloak/occpl-realm.json): real tokens, the Keycloak admin client, first Super Admin
 * bootstrap and MFA enrolment through Keycloak's login pages.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0", "management.server.port=0"})
class KeycloakIdentityIT {

    private static final String ADMIN_CLIENT_SECRET = "integration-test-secret";
    private static final String BOOTSTRAP_EMAIL = "root@occpl.test";
    private static final String PASSWORD = "Integration-Test-Passw0rd!";

    @SuppressWarnings("resource")
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("keycloak/keycloak:26.8.1")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withEnv("KEYCLOAK_ADMIN_CLIENT_SECRET", ADMIN_CLIENT_SECRET)
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../../infra/docker/keycloak/occpl-realm.json")),
                    "/opt/keycloak/data/import/occpl-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        KEYCLOAK.start();
    }

    static String baseUrl() {
        return "http://localhost:" + KEYCLOAK.getMappedPort(8080);
    }

    static String realmUrl() {
        return baseUrl() + "/realms/occpl";
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakIdentityIT::realmUrl);
        registry.add("occpl.identity.keycloak.base-url", KeycloakIdentityIT::baseUrl);
        registry.add("occpl.identity.keycloak.client-secret", () -> ADMIN_CLIENT_SECRET);
        registry.add("occpl.identity.bootstrap-super-admin-email", () -> BOOTSTRAP_EMAIL);
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    IdentityProviderAdmin idp;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void createTestClients() throws Exception {
        // Password-grant clients exist only for tests; the realm itself allows no password grant.
        createClient(Map.of("clientId", "it-direct", "publicClient", true, "directAccessGrantsEnabled", true,
                "standardFlowEnabled", false, "protocolMappers", List.of(Map.of("name", "aud",
                        "protocol", "openid-connect", "protocolMapper", "oidc-audience-mapper",
                        "config", Map.of("included.custom.audience", "occpl-core", "access.token.claim", "true")))));
        createClient(Map.of("clientId", "it-noaud", "publicClient", true, "directAccessGrantsEnabled", true,
                "standardFlowEnabled", false));
    }

    @Test
    void adminClientManagesUsersThroughServiceAccount() {
        String email = "managed-" + UUID.randomUUID() + "@occpl.test";
        String subject = idp.createUser(email, "Managed", "User", Set.of(RequiredAction.UPDATE_PASSWORD));

        IdpUser created = idp.findByEmail(email).orElseThrow();
        assertThat(created.subject()).isEqualTo(subject);
        assertThat(created.emailVerified()).isFalse();
        assertThat(created.requiredActions()).containsExactly("UPDATE_PASSWORD");

        idp.addRequiredActions(subject, Set.of(RequiredAction.CONFIGURE_TOTP));
        assertThat(idp.findBySubject(subject).orElseThrow().requiredActions())
                .containsExactlyInAnyOrder("UPDATE_PASSWORD", "CONFIGURE_TOTP");
        assertThat(idp.hasCredential(subject, "otp")).isFalse();

        idp.setEnabled(subject, false);
        assertThat(idp.findBySubject(subject).orElseThrow().enabled()).isFalse();
        idp.logout(subject);
        assertThat(idp.findBySubject(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void acceptsRealmTokensWithCoreAudienceOnly() throws Exception {
        String email = "token-" + UUID.randomUUID() + "@occpl.test";
        readyUser(email);

        HttpResponse<String> me = api("GET", "/api/v1/me", passwordGrant("it-direct", email), null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(me.body()).get("email").asString()).isEqualTo(email);

        assertThat(api("GET", "/api/v1/me", passwordGrant("it-noaud", email), null).statusCode()).isEqualTo(401);
        assertThat(api("GET", "/api/v1/me", masterAdminToken(), null).statusCode()).isEqualTo(401);
    }

    @Test
    void bootstrapsSuperAdminAndRequiresMfaBeforeGrantingPlatformPermissions() throws Exception {
        String subject = readyUser(BOOTSTRAP_EMAIL);

        // 1. Password-only token: SUPER_ADMIN assigned, but platform permissions withheld.
        JsonNode me = JSON.readTree(api("GET", "/api/v1/me", passwordGrant("it-direct", BOOTSTRAP_EMAIL), null).body());
        assertThat(me.get("platform").get("roles").toString()).contains("SUPER_ADMIN");
        assertThat(me.get("platform").get("permissions").size()).isZero();
        assertThat(me.get("platform").get("mfaRequired").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'platform.super_admin.bootstrapped'",
                Integer.class)).isEqualTo(1);
        assertThat(idp.findBySubject(subject).orElseThrow().requiredActions()).contains("CONFIGURE_TOTP");

        // 2. Browser login: Keycloak makes the user enrol an authenticator app.
        KeycloakLoginFlow enrolment = new KeycloakLoginFlow(realmUrl(), "occpl-admin-portal");
        enrolment.login(BOOTSTRAP_EMAIL, PASSWORD, null, 0);
        assertThat(enrolment.otpSecret()).isNotBlank();
        assertThat(idp.hasCredential(subject, "otp")).isTrue();

        // 3. Next login asks for the OTP; the token now carries amr=otp and platform permissions apply.
        KeycloakLoginFlow.awaitNextOtpWindow();
        String mfaToken = new KeycloakLoginFlow(realmUrl(), "occpl-admin-portal")
                .login(BOOTSTRAP_EMAIL, PASSWORD, enrolment.otpSecret(), 0);
        JsonNode mfaMe = JSON.readTree(api("GET", "/api/v1/me", mfaToken, null).body());
        assertThat(mfaMe.get("platform").get("mfaRequired").asBoolean()).isFalse();
        assertThat(mfaMe.get("platform").get("permissions").toString()).contains("platform.roles.manage");

        String userId = mfaMe.get("id").asString();
        HttpResponse<String> removeLast = api("PUT", "/api/v1/platform/users/" + userId + "/roles", mfaToken,
                "{\"roles\":[]}");
        assertThat(removeLast.statusCode()).isEqualTo(409);
        assertThat(removeLast.body()).contains("LAST_ADMIN");
    }

    /** Creates an enabled user with verified email and a password, via the Core admin client and master admin. */
    private String readyUser(String email) throws Exception {
        String subject = idp.createUser(email, "Test", "User", Set.of());
        String admin = masterAdminToken();
        send("PUT", baseUrl() + "/admin/realms/occpl/users/" + subject, admin, "{\"emailVerified\":true}");
        send("PUT", baseUrl() + "/admin/realms/occpl/users/" + subject + "/reset-password", admin,
                "{\"type\":\"password\",\"value\":\"" + PASSWORD + "\",\"temporary\":false}");
        return subject;
    }

    private static String passwordGrant(String clientId, String username) throws Exception {
        return token(realmUrl(), "grant_type=password&client_id=" + clientId + "&username="
                + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password="
                + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8));
    }

    private static String masterAdminToken() throws Exception {
        return token(baseUrl() + "/realms/master", "grant_type=password&client_id=admin-cli&username=admin&password=admin");
    }

    private static String token(String realm, String form) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(realm + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("access_token").asString();
    }

    private static void createClient(Map<String, Object> client) throws Exception {
        HttpResponse<String> response = send("POST", baseUrl() + "/admin/realms/occpl/clients", masterAdminToken(),
                JSON.writeValueAsString(client));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private static HttpResponse<String> send(String method, String url, String bearer, String json) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode() / 100).as(method + " " + url + ": " + response.body()).isEqualTo(2);
        return response;
    }

    private HttpResponse<String> api(String method, String path, String bearer, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
