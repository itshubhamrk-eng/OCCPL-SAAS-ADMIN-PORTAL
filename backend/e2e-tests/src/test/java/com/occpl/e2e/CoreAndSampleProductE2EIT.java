package com.occpl.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.occpl.platform.CorePlatformApplication;
import com.occpl.platform.identity.KeycloakLoginFlow;
import com.occpl.platform.identity.api.IdentityProviderAdmin;
import com.occpl.platform.provisioning.internal.ProvisioningRunner;
import com.occpl.platform.support.KeycloakTestServer;
import com.occpl.platform.support.MySqlTestDatabase;
import com.occpl.platform.support.TestData;
import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole product integration with real components: Keycloak 26.8.1 (project realm import, real
 * client-credentials and user tokens), the Core Platform and the sample product as a separate
 * process with its own MySQL database.
 *
 * <p>Flow: Super Admin with MFA registers the product, plan and subscription → Core provisions the
 * company in the product → a member works with notes in the product, which checks access and
 * reports usage to Core → the subscription ends → the product refuses access → after the retention
 * period Core removes the tenant from the product.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = CorePlatformApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0", "management.server.port=0"})
class CoreAndSampleProductE2EIT {

    private static final String ADMIN_EMAIL = "root@occpl.test";
    private static final String MEMBER_EMAIL = "member@acme.test";
    private static final String OUTSIDER_EMAIL = "outsider@other.test";
    private static final String PASSWORD = KeycloakTestServer.PASSWORD;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("resource")
    private static final MySQLContainer SAMPLE_DB = new MySQLContainer("mysql:9.7").withDatabaseName("occpl_sample");
    private static Process sample;

    static {
        SAMPLE_DB.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakTestServer::realmUrl);
        registry.add("occpl.identity.keycloak.base-url", KeycloakTestServer::baseUrl);
        registry.add("occpl.identity.keycloak.client-secret", () -> KeycloakTestServer.ADMIN_CLIENT_SECRET);
        registry.add("occpl.identity.bootstrap-super-admin-email", () -> ADMIN_EMAIL);
        registry.add("occpl.provisioning.token-uri",
                () -> KeycloakTestServer.realmUrl() + "/protocol/openid-connect/token");
        registry.add("occpl.provisioning.client-secret", () -> KeycloakTestServer.CORE_SERVICES_CLIENT_SECRET);
    }

    @AfterAll
    static void stopSample() {
        if (sample != null) {
            sample.destroy();
        }
    }

    @LocalServerPort int corePort;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityProviderAdmin idp;
    @Autowired ProvisioningRunner runner;

    String sampleUrl;

    @Test
    void productLifecycleAcrossCoreKeycloakAndTheSampleProduct() throws Exception {
        startSample();
        // test-only password-grant client issuing user tokens for the sample product's audience
        KeycloakTestServer.createClient(Map.of("clientId", "it-sample-direct", "publicClient", true,
                "directAccessGrantsEnabled", true, "standardFlowEnabled", false, "protocolMappers", List.of(Map.of(
                        "name", "aud", "protocol", "openid-connect", "protocolMapper", "oidc-audience-mapper",
                        "config", Map.of("included.custom.audience", "product-sample", "access.token.claim", "true")))));

        String admin = superAdminWithMfa();

        // --- catalogue, plan, company and member (Core APIs, real tokens)
        String productId = core("POST", "/api/v1/products", admin, """
                {"code":"sample","name":"Sample Notes","appUrl":"%s","apiBaseUrl":"%s",
                 "apiAudience":"product-sample","serviceClientId":"product-sample-service"}""".formatted(sampleUrl, sampleUrl),
                201).get("id").asString();
        core("POST", "/api/v1/products/" + productId + "/features", admin,
                "{\"code\":\"notes\",\"name\":\"Notes\",\"type\":\"LIMIT\",\"unit\":\"RECORDS\",\"resetPeriod\":\"NONE\"}", 201);
        core("POST", "/api/v1/products/" + productId + "/versions", admin, """
                {"config":{"schemaVersion":1,"launch":{"path":"/companies/{companyId}"},
                 "provisioning":{"mode":"SYNC","path":"/provisioning/v1/tenants/{companyId}","timeoutSeconds":10,"maxAttempts":3}}}""", 201);
        core("POST", "/api/v1/products/" + productId + "/versions/1/publish", admin, null, 200);
        core("POST", "/api/v1/products/" + productId + "/activate", admin, null, 200);
        String planId = core("POST", "/api/v1/plans", admin, """
                {"code":"notes-basic","plan":{"name":"Notes Basic","billingPeriod":"MONTHLY","trialDays":0,
                 "prices":[{"currency":"INR","amountMinor":49900}],
                 "products":[{"productId":"%s","features":[{"code":"notes","limit":2}]}]}}""".formatted(productId), 201)
                .get("id").asString();
        core("POST", "/api/v1/plans/" + planId + "/publish", admin, null, 200);

        TestData data = new TestData(jdbc);
        UUID company = data.company("acme", "ACTIVE");
        String member = userToken(MEMBER_EMAIL, "it-direct");
        UUID memberId = UUID.fromString(core("GET", "/api/v1/me", member, null, 200).get("id").asString());
        data.membership(company, memberId, "ACTIVE", TestData.COMPANY_USER_ROLE);
        String outsider = userToken(OUTSIDER_EMAIL, "it-direct");
        core("GET", "/api/v1/me", outsider, null, 200);

        // --- subscription → provisioning through the real Keycloak service token
        String subscription = core("POST", "/api/v1/companies/" + company + "/subscriptions", admin,
                "{\"planId\":\"" + planId + "\",\"currency\":\"INR\",\"startTrial\":false}", 201).get("id").asString();
        runner.runDue();
        JsonNode jobs = core("GET", "/api/v1/platform/provisioning-jobs?companyId=" + company, admin, null, 200);
        assertThat(jobs.get("items").get(0).get("status").asString()).as(jobs.toString()).isEqualTo("SUCCEEDED");
        assertThat(sampleTenantStatus(company)).isEqualTo("ACTIVE");
        JsonNode launcher = core("GET", "/api/v1/companies/" + company + "/products", member, null, 200);
        assertThat(launcher.get(0).get("provisioningState").asString()).isEqualTo("PROVISIONED");
        assertThat(launcher.get(0).get("launchUrl").asString()).isEqualTo(sampleUrl + "/companies/" + company);

        // --- the member uses the product; the product asks Core with its own service token
        String memberForSample = KeycloakTestServer.passwordGrant("it-sample-direct", MEMBER_EMAIL);
        String outsiderForSample = KeycloakTestServer.passwordGrant("it-sample-direct", OUTSIDER_EMAIL);
        assertThat(sample("GET", company, "/notes", member, null).statusCode()).as("Core token has the wrong audience")
                .isEqualTo(401);
        HttpResponse<String> first = sample("POST", company, "/notes", memberForSample, "{\"title\":\"Kick-off\"}");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(sample("POST", company, "/notes", memberForSample, "{\"title\":\"Follow-up\"}").statusCode()).isEqualTo(201);
        HttpResponse<String> overLimit = sample("POST", company, "/notes", memberForSample, "{\"title\":\"Too many\"}");
        assertThat(overLimit.statusCode()).as(overLimit.body()).isEqualTo(409);
        assertThat(JSON.readTree(sample("GET", company, "/notes", memberForSample, null).body()).size()).isEqualTo(2);
        assertThat(sample("GET", company, "/notes", outsiderForSample, null).statusCode()).isEqualTo(404);
        JsonNode notesUsage = usage(company, admin);
        assertThat(notesUsage.get("used").asLong()).isEqualTo(2);

        String firstId = JSON.readTree(first.body()).get("id").asString();
        assertThat(sample("DELETE", company, "/notes/" + firstId, memberForSample, null).statusCode()).isEqualTo(204);
        assertThat(usage(company, admin).get("used").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from usage_record where idempotency_key like 'sample:note-%'",
                Integer.class)).isEqualTo(3);

        // --- the subscription ends: access stops at once, removal waits for the retention period
        core("POST", "/api/v1/companies/" + company + "/subscriptions/" + subscription + "/cancel", admin,
                "{\"mode\":\"IMMEDIATE\"}", 200);
        HttpResponse<String> afterCancel = sample("POST", company, "/notes", memberForSample, "{\"title\":\"Late\"}");
        assertThat(afterCancel.statusCode()).as(afterCancel.body()).isEqualTo(403);
        assertThat(afterCancel.body()).contains("NO_ENTITLEMENT");
        runner.runDue();
        assertThat(sampleTenantStatus(company)).isEqualTo("ACTIVE");

        jdbc.update("update provisioning_job set next_attempt_at = UTC_TIMESTAMP(6) where operation = 'DEPROVISION' "
                + "and company_id = UUID_TO_BIN(?)", company.toString());
        runner.runDue();
        assertThat(sampleTenantStatus(company)).isEqualTo("REMOVED");
        assertThat(sampleNoteCount(company)).isZero();
        assertThat(jdbc.queryForObject("select state from company_product_provisioning where company_id = UUID_TO_BIN(?)",
                String.class, company.toString())).isEqualTo("DEPROVISIONED");
    }

    // ------------------------------------------------------------------ helpers

    /** Bootstrap Super Admin, enrol TOTP through Keycloak's pages, then sign in with the OTP. */
    private String superAdminWithMfa() throws Exception {
        String subject = idp.createUser(ADMIN_EMAIL, "Root", "Admin", Set.of());
        KeycloakTestServer.makeReady(subject);
        core("GET", "/api/v1/me", KeycloakTestServer.passwordGrant("it-direct", ADMIN_EMAIL), null, 200);
        KeycloakLoginFlow enrolment = new KeycloakLoginFlow(KeycloakTestServer.realmUrl(), "occpl-admin-portal");
        enrolment.login(ADMIN_EMAIL, PASSWORD, null, 0);
        KeycloakLoginFlow.awaitNextOtpWindow();
        String token = new KeycloakLoginFlow(KeycloakTestServer.realmUrl(), "occpl-admin-portal")
                .login(ADMIN_EMAIL, PASSWORD, enrolment.otpSecret(), 0);
        assertThat(core("GET", "/api/v1/me", token, null, 200).get("platform").get("mfaRequired").asBoolean()).isFalse();
        return token;
    }

    private String userToken(String email, String client) throws Exception {
        String subject = idp.createUser(email, "Test", "User", Set.of());
        KeycloakTestServer.makeReady(subject);
        return KeycloakTestServer.passwordGrant(client, email);
    }

    private JsonNode usage(UUID company, String token) throws Exception {
        JsonNode entitlements = core("GET", "/api/v1/companies/" + company + "/entitlements", token, null, 200);
        for (JsonNode feature : entitlements.get(0).get("features")) {
            if (feature.get("code").asString().equals("notes")) {
                return feature;
            }
        }
        throw new AssertionError("notes feature missing: " + entitlements);
    }

    private void startSample() throws Exception {
        Path jar = Path.of(System.getProperty("productSampleJar", "target/apps/product-sample.jar"));
        assertThat(jar).as("build product-sample first (mvn verify from backend/)").exists();
        int port = freePort();
        int managementPort = freePort();
        sampleUrl = "http://localhost:" + port;
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder builder = new ProcessBuilder(java, "-jar", jar.toString());
        Map<String, String> env = builder.environment();
        env.put("PORT", String.valueOf(port));
        env.put("MANAGEMENT_PORT", String.valueOf(managementPort));
        env.put("DB_URL", SAMPLE_DB.getJdbcUrl());
        env.put("DB_USERNAME", SAMPLE_DB.getUsername());
        env.put("DB_PASSWORD", SAMPLE_DB.getPassword());
        env.put("OIDC_ISSUER_URI", KeycloakTestServer.realmUrl());
        env.put("CORE_BASE_URL", "http://localhost:" + corePort);
        env.put("CORE_TOKEN_URI", KeycloakTestServer.realmUrl() + "/protocol/openid-connect/token");
        env.put("CORE_CLIENT_SECRET", KeycloakTestServer.SAMPLE_SERVICE_CLIENT_SECRET);
        env.remove("JAVA_TOOL_OPTIONS");
        File log = Path.of("target/product-sample.log").toFile();
        sample = builder.redirectErrorStream(true).redirectOutput(log).start();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (Instant.now().isBefore(deadline)) {
            if (!sample.isAlive()) {
                throw new IllegalStateException("product-sample exited:\n" + Files.readString(log.toPath()));
            }
            try {
                HttpResponse<String> health = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/health")).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (health.statusCode() == 200) {
                    return;
                }
            } catch (java.io.IOException notYet) {
                // starting
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("product-sample did not start:\n" + Files.readString(log.toPath()));
    }

    private String sampleTenantStatus(UUID company) {
        return sampleDb().queryForObject("select status from tenant where company_id = ?", String.class,
                company.toString());
    }

    private int sampleNoteCount(UUID company) {
        return sampleDb().queryForObject("select count(*) from note where company_id = ?", Integer.class,
                company.toString());
    }

    private JdbcTemplate sampleDb() {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(SAMPLE_DB.getJdbcUrl(),
                SAMPLE_DB.getUsername(), SAMPLE_DB.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private JsonNode core(String method, String path, String token, String body, int expectedStatus) throws Exception {
        HttpResponse<String> response = send(method, URI.create("http://localhost:" + corePort + path), token, body);
        assertThat(response.statusCode()).as(method + " " + path + ": " + response.body()).isEqualTo(expectedStatus);
        return response.body().isBlank() ? null : JSON.readTree(response.body());
    }

    private HttpResponse<String> sample(String method, UUID company, String path, String token, String body)
            throws Exception {
        return send(method, URI.create(sampleUrl + "/api/companies/" + company + path), token, body);
    }

    private static HttpResponse<String> send(String method, URI uri, String token, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
