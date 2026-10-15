package com.occpl.platform.membership;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.occpl.platform.identity.api.IdentityProviderAdmin;
import com.occpl.platform.notification.internal.NotificationDispatcher;
import com.occpl.platform.support.KeycloakTestServer;
import com.occpl.platform.support.MySqlTestDatabase;
import com.occpl.platform.support.TestData;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end invitations with real Keycloak and real SMTP (GreenMail): a new Company Admin receives
 * Keycloak's account-setup email, sets a password, lands on the accept link and joins the company;
 * an existing user receives the platform's own invitation email. MFA is disabled here because it
 * is covered by KeycloakIdentityIT.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"server.port=0",
        "management.server.port=0", "occpl.identity.mfa.platform-required=false",
        "spring.mail.host=127.0.0.1", "spring.mail.port=" + KeycloakTestServer.SMTP_PORT})
class KeycloakInvitationIT {

    private static final String ROOT = "invitations-root@occpl.test";

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP).withPerMethodLifecycle(false);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakTestServer::realmUrl);
        registry.add("occpl.identity.keycloak.base-url", KeycloakTestServer::baseUrl);
        registry.add("occpl.identity.keycloak.client-secret", () -> KeycloakTestServer.ADMIN_CLIENT_SECRET);
        registry.add("occpl.identity.bootstrap-super-admin-email", () -> ROOT);
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @Autowired IdentityProviderAdmin idp;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;

    @Test
    void newCompanyAdminSetsPasswordAndAcceptsThroughEmailLink() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        String root = rootToken();
        String adminEmail = "new-admin-" + run + "@occpl.test";

        HttpResponse<String> created = api("POST", "/api/v1/companies", root, """
                {"code":"e2e-%s","legalName":"E2E %s Pvt Ltd","displayName":"E2E %s","country":"IN",
                 "initialAdmin":{"email":"%s","firstName":"New","lastName":"Admin"}}""".formatted(run, run, run, adminEmail));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String companyId = JSON.readTree(created.body()).get("id").asString();

        // Keycloak's account-setup email (sent synchronously when the invitation committed).
        String keycloakMail = mailTo(adminEmail);
        Matcher link = Pattern.compile("(http://[^\"\\s<>]+/login-actions/action-token\\?[^\"\\s<>]+)").matcher(keycloakMail);
        assertThat(link.find()).as(keycloakMail).isTrue();

        String landing = new com.occpl.platform.identity.KeycloakLoginFlowAccess()
                .completeActionsEmail(link.group(1).replace("&amp;", "&"), "Fresh-Admin-Passw0rd!");
        assertThat(landing).startsWith("http://localhost:4200/invitations/accept?token=");
        String token = URLDecoder.decode(landing.substring(landing.indexOf("token=") + 6).split("&")[0],
                StandardCharsets.UTF_8);

        String adminToken = KeycloakTestServer.passwordGrant("it-direct", adminEmail, "Fresh-Admin-Passw0rd!");
        HttpResponse<String> accepted = api("POST", "/api/v1/invitations/accept", adminToken,
                "{\"token\":\"" + token + "\"}");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);

        JsonNode me = JSON.readTree(api("GET", "/api/v1/me", adminToken, null).body());
        assertThat(me.get("memberships").get(0).get("companyId").asString()).isEqualTo(companyId);
        assertThat(me.get("memberships").get(0).get("roles").toString()).contains("COMPANY_ADMIN");
        assertThat(api("GET", "/api/v1/companies/" + companyId + "/memberships", adminToken, null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void existingUserReceivesPlatformInvitationEmail() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        String root = rootToken();
        String memberEmail = "existing-" + run + "@occpl.test";
        KeycloakTestServer.makeReady(idp.createUser(memberEmail, "Existing", "Member", Set.of()));

        String companyId = JSON.readTree(api("POST", "/api/v1/companies", root, """
                {"code":"e2e-x-%s","legalName":"X %s Pvt Ltd","displayName":"X %s","country":"IN",
                 "initialAdmin":{"email":"owner-%s@occpl.test"}}""".formatted(run, run, run, run)).body())
                .get("id").asString();
        HttpResponse<String> invited = api("POST", "/api/v1/companies/" + companyId + "/invitations", root,
                "{\"email\":\"" + memberEmail + "\",\"roles\":[\"COMPANY_USER\"]}");
        assertThat(invited.statusCode()).as(invited.body()).isEqualTo(201);
        dispatcher.dispatchDue();

        String mail = mailTo(memberEmail);
        assertThat(mail).contains("X " + run);
        Matcher token = Pattern.compile("token=([A-Za-z0-9_%-]+)").matcher(mail);
        assertThat(token.find()).isTrue();
        HttpResponse<String> accepted = api("POST", "/api/v1/invitations/accept",
                KeycloakTestServer.passwordGrant("it-direct", memberEmail),
                "{\"token\":\"" + URLDecoder.decode(token.group(1), StandardCharsets.UTF_8) + "\"}");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
    }

    private String rootToken() throws Exception {
        if (idp.findByEmail(ROOT).isEmpty()) {
            KeycloakTestServer.makeReady(idp.createUser(ROOT, "Root", "Admin", Set.of()));
        }
        String token = KeycloakTestServer.passwordGrant("it-direct", ROOT);
        assertThat(api("GET", "/api/v1/me", token, null).statusCode()).isEqualTo(200);
        // The shared test database may already have a Super Admin (so no bootstrap): grant the role directly.
        jdbc.update("""
                insert ignore into platform_role_assignment (user_id, role_id, assigned_at)
                select id, UUID_TO_BIN(?), UTC_TIMESTAMP(6) from platform_user where email = ?""",
                TestData.SUPER_ADMIN_ROLE.toString(), ROOT);
        return token;
    }

    private static String mailTo(String recipient) throws Exception {
        smtp.waitForIncomingEmail(10_000, 1);
        return Arrays.stream(smtp.getReceivedMessages())
                .filter(m -> recipientIs(m, recipient))
                .reduce((a, b) -> b)
                .map(GreenMailUtil::getBody)
                .map(body -> body.replace("=\r\n", "").replace("=3D", "="))
                .orElseThrow(() -> new AssertionError("No email to " + recipient));
    }

    private static boolean recipientIs(MimeMessage message, String recipient) {
        try {
            return Arrays.stream(message.getRecipients(Message.RecipientType.TO))
                    .anyMatch(a -> a.toString().contains(recipient));
        } catch (Exception e) {
            return false;
        }
    }

    private HttpResponse<String> api(String method, String path, String bearer, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
