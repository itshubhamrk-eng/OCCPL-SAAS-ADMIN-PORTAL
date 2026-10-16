package com.occpl.platform.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.occpl.platform.identity.api.IdentityProviderAdmin;
import com.occpl.platform.membership.internal.InvitationService;
import com.occpl.platform.support.MockTokens;
import com.occpl.platform.support.MySqlTestDatabase;
import com.occpl.platform.support.TestData;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Company and membership management against MySQL. Keycloak is mocked; the invitation token is
 * captured from the account-setup redirect (new users) or the queued email (existing users).
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class CompanyAndMembershipIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired InvitationService invitationService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean IdentityProviderAdmin idp;

    MockTokens tokens;
    TestData data;
    String run;

    @BeforeEach
    void setUp() throws Exception {
        tokens = new MockTokens(jwtDecoder);
        data = new TestData(jdbc);
        run = UUID.randomUUID().toString().substring(0, 8);
        given(idp.findByEmail(anyString())).willReturn(Optional.empty());
        given(idp.createUser(anyString(), any(), any(), any())).willAnswer(i -> "kc-" + UUID.randomUUID());
        signIn("root", true);
        data.platformRole(data.userIdByEmail(email("root")), TestData.SUPER_ADMIN_ROLE);
    }

    // ---------------------------------------------------------------- companies

    @Test
    void createsCompanyWithValidationAndInvitesInitialAdmin() throws Exception {
        perform(post("/api/v1/companies"), "root", "{\"code\":\"Bad Code\",\"legalName\":\"\",\"displayName\":\"x\","
                + "\"country\":\"india\",\"initialAdmin\":{\"email\":\"not-an-email\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(4));

        JsonNode company = createCompany("acme");
        UUID companyId = UUID.fromString(company.get("id").asString());
        assertThat(company.get("status").asString()).isEqualTo("ACTIVE");

        perform(post("/api/v1/companies"), "root", companyBody("acme-" + run, email("other-admin")))
                .andExpect(status().isConflict());

        signIn("plain", false);
        perform(post("/api/v1/companies"), "plain", companyBody("plain-" + run, email("x")))
                .andExpect(status().isForbidden());

        JsonNode invitations = body(perform(get("/api/v1/companies/" + companyId + "/invitations"), "root", null)
                .andExpect(status().isOk()));
        assertThat(invitations.get("items").size()).isEqualTo(1);
        assertThat(invitations.get("items").get(0).get("roles").toString()).contains("COMPANY_ADMIN");
        assertThat(invitations.get("items").get(0).get("status").asString()).isEqualTo("PENDING");
        assertThat(lastAccountSetupToken()).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'company.created' and company_id = UUID_TO_BIN(?)",
                Integer.class, companyId.toString())).isEqualTo(1);
        perform(get("/api/v1/companies/" + companyId + "/status-history"), "root", null)
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void searchesCompaniesWithFiltersSortingAndPaging() throws Exception {
        createCompany("zeta");
        createCompany("alpha");
        UUID beta = UUID.fromString(createCompany("beta").get("id").asString());
        perform(post("/api/v1/companies/" + beta + "/suspend"), "root", "{\"reason\":\"test\"}").andExpect(status().isOk());

        perform(get("/api/v1/companies?q=alpha-" + run), "root", null)
                .andExpect(jsonPath("$.totalItems").value(1));
        perform(get("/api/v1/companies?status=SUSPENDED&q=beta-" + run), "root", null)
                .andExpect(jsonPath("$.items[0].id").value(beta.toString()));
        JsonNode page = body(perform(get("/api/v1/companies?q=%25&size=1&sort=code,asc"), "root", null)
                .andExpect(status().isOk()));
        assertThat(page.get("totalItems").asLong()).isZero(); // '%' is escaped, not a wildcard
        perform(get("/api/v1/companies?sort=legalName"), "root", null).andExpect(status().isBadRequest());
        perform(get("/api/v1/companies?size=101"), "root", null).andExpect(status().isBadRequest());
    }

    @Test
    void companyAdminsMayRenameButNotChangeLegalIdentity() throws Exception {
        UUID companyId = companyWithActiveAdmin("edit", "admin");

        perform(patch("/api/v1/companies/" + companyId), "admin", "{\"displayName\":\"Renamed\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Renamed"))
                .andExpect(header().exists("ETag"));
        perform(patch("/api/v1/companies/" + companyId), "admin", "{\"legalName\":\"Evil Corp\"}")
                .andExpect(status().isForbidden());
        perform(patch("/api/v1/companies/" + companyId), "root", "{\"legalName\":\"New Legal Name Pvt Ltd\"}")
                .andExpect(status().isOk());
        perform(patch("/api/v1/companies/" + companyId).header("If-Match", "\"0\""), "root",
                "{\"displayName\":\"Stale\"}")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
    }

    @Test
    void enforcesCompanyLifecycleTransitions() throws Exception {
        UUID companyId = companyWithActiveAdmin("life", "admin");

        perform(post("/api/v1/companies/" + companyId + "/suspend"), "root", "{}").andExpect(status().isBadRequest());
        perform(post("/api/v1/companies/" + companyId + "/suspend"), "root", "{\"reason\":\"Unpaid\"}")
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        perform(post("/api/v1/companies/" + companyId + "/suspend"), "root", "{\"reason\":\"again\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        perform(post("/api/v1/companies/" + companyId + "/suspend"), "admin", "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden());
        perform(get("/api/v1/companies/" + companyId), "admin", null).andExpect(status().isOk());
        perform(post("/api/v1/companies/" + companyId + "/activate"), "root", null)
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        perform(post("/api/v1/companies/" + companyId + "/deactivate"), "root", "{\"reason\":\"Closed\"}")
                .andExpect(jsonPath("$.status").value("DEACTIVATED"));
        perform(get("/api/v1/companies/" + companyId), "admin", null).andExpect(status().isNotFound());
        perform(get("/api/v1/companies/" + companyId + "/status-history"), "root", null)
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].toStatus").value("DEACTIVATED"))
                .andExpect(jsonPath("$[0].reason").value("Closed"));
    }

    @Test
    void settingsRestrictInvitationDomainsAndContactsAreManaged() throws Exception {
        UUID companyId = companyWithActiveAdmin("cfg", "admin");

        perform(put("/api/v1/companies/" + companyId + "/settings"), "admin",
                "{\"timeZone\":\"Asia/Kolkata\",\"locale\":\"en-IN\",\"allowedEmailDomains\":[\"Not A Domain\"]}")
                .andExpect(status().isBadRequest());
        perform(put("/api/v1/companies/" + companyId + "/settings"), "admin",
                "{\"timeZone\":\"Asia/Kolkata\",\"locale\":\"en-IN\",\"allowedEmailDomains\":[\"acme.test\"]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowedEmailDomains[0]").value("acme.test"));
        perform(post("/api/v1/companies/" + companyId + "/invitations"), "admin",
                "{\"email\":\"someone@other.test\",\"roles\":[\"COMPANY_USER\"]}")
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/companies/" + companyId + "/invitations"), "admin",
                "{\"email\":\"someone-" + run + "@acme.test\",\"roles\":[\"COMPANY_USER\"]}")
                .andExpect(status().isCreated());

        perform(put("/api/v1/companies/" + companyId + "/contacts/billing"), "admin",
                "{\"name\":\"Accounts\",\"email\":\"Billing@Acme.test\",\"phone\":\"+919876543210\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("billing@acme.test"));
        perform(get("/api/v1/companies/" + companyId + "/contacts"), "admin", null)
                .andExpect(jsonPath("$.length()").value(2));
        // The primary contact must carry a phone: provisioned products create their first admin from it.
        perform(put("/api/v1/companies/" + companyId + "/contacts/PRIMARY"), "admin",
                "{\"name\":\"Owner\",\"email\":\"owner@acme.test\"}")
                .andExpect(status().isBadRequest());
        perform(delete("/api/v1/companies/" + companyId + "/contacts/PRIMARY"), "admin", null)
                .andExpect(status().isConflict());
        perform(delete("/api/v1/companies/" + companyId + "/contacts/BILLING"), "admin", null)
                .andExpect(status().isNoContent());
        perform(put("/api/v1/companies/" + companyId + "/contacts/HOME"), "admin",
                "{\"name\":\"x\",\"email\":\"x@acme.test\"}")
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- invitations

    @Test
    void invitationAcceptanceRules() throws Exception {
        UUID companyId = companyWithActiveAdmin("inv", "admin");
        String bobEmail = email("bob");

        perform(post("/api/v1/companies/" + companyId + "/invitations"), "admin",
                "{\"email\":\"" + bobEmail + "\",\"roles\":[\"COMPANY_USER\"]}").andExpect(status().isCreated());
        String token = lastAccountSetupToken();
        perform(post("/api/v1/companies/" + companyId + "/invitations"), "admin",
                "{\"email\":\"" + bobEmail.toUpperCase() + "\",\"roles\":[\"COMPANY_USER\"]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVITATION_PENDING"));

        signIn("eve", false);
        accept("eve", token).andExpect(status().isNotFound());
        accept("eve", "not-a-real-token").andExpect(status().isNotFound());

        signIn("bob", false);
        accept("bob", token).andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.roles[0]").value("COMPANY_USER"));
        accept("bob", token).andExpect(status().isOk()); // idempotent
        perform(get("/api/v1/me"), "bob", null).andExpect(jsonPath("$.memberships[0].companyId").value(companyId.toString()));
        perform(post("/api/v1/companies/" + companyId + "/invitations"), "admin",
                "{\"email\":\"" + bobEmail + "\",\"roles\":[\"COMPANY_USER\"]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_MEMBERSHIP"));
    }

    @Test
    void expiredResentAndRevokedInvitations() throws Exception {
        UUID companyId = companyWithActiveAdmin("exp", "admin");
        JsonNode invitation = body(perform(post("/api/v1/companies/" + companyId + "/invitations"), "admin",
                "{\"email\":\"" + email("carol") + "\",\"roles\":[\"COMPANY_USER\"]}"));
        String invitationId = invitation.get("id").asString();
        String firstToken = lastAccountSetupToken();
        jdbc.update("update invitation set expires_at = UTC_TIMESTAMP(6) - interval 1 minute where id = UUID_TO_BIN(?)",
                invitationId);

        signIn("carol", false);
        accept("carol", firstToken).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
        assertThat(invitationStatus(invitationId)).isEqualTo("EXPIRED");

        perform(post("/api/v1/companies/" + companyId + "/invitations/" + invitationId + "/resend"), "admin", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        String secondToken = lastAccountSetupToken();
        assertThat(secondToken).isNotEqualTo(firstToken);
        accept("carol", firstToken).andExpect(status().isNotFound());

        perform(post("/api/v1/companies/" + companyId + "/invitations/" + invitationId + "/revoke"), "admin", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        accept("carol", secondToken).andExpect(status().isNotFound());
        perform(get("/api/v1/companies/" + companyId + "/memberships?status=DEACTIVATED"), "admin", null)
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void existingAccountsGetPlatformInvitationEmail() throws Exception {
        UUID companyId = companyWithActiveAdmin("existing", "admin");
        String daveEmail = email("dave");
        given(idp.findByEmail(daveEmail)).willReturn(Optional.of(
                new IdentityProviderAdmin.IdpUser("kc-dave", daveEmail, true, true, Set.of())));

        perform(post("/api/v1/companies/" + companyId + "/invitations"), "admin",
                "{\"email\":\"" + daveEmail + "\",\"roles\":[\"COMPANY_USER\"]}").andExpect(status().isCreated());
        String body = jdbc.queryForObject("select body_text from notification where recipient_email = ? "
                + "and template = 'invitation' order by created_at desc limit 1", String.class, daveEmail);
        Matcher m = Pattern.compile("token=([A-Za-z0-9_%-]+)").matcher(body);
        assertThat(m.find()).isTrue();

        signIn("dave", false);
        accept("dave", URLDecoder.decode(m.group(1), StandardCharsets.UTF_8)).andExpect(status().isOk());
    }

    @Test
    void expiryJobMarksOverdueInvitations() throws Exception {
        UUID companyId = companyWithActiveAdmin("job", "admin");
        String id = body(perform(post("/api/v1/companies/" + companyId + "/invitations"), "admin",
                "{\"email\":\"" + email("late") + "\",\"roles\":[\"COMPANY_USER\"]}")).get("id").asString();
        jdbc.update("update invitation set expires_at = UTC_TIMESTAMP(6) - interval 1 hour where id = UUID_TO_BIN(?)", id);

        assertThat(invitationService.expireOverdue()).isGreaterThanOrEqualTo(1);
        assertThat(invitationStatus(id)).isEqualTo("EXPIRED");
    }

    // ---------------------------------------------------------------- memberships

    @Test
    void escalationSelfChangeAndLastAdminRules() throws Exception {
        UUID companyId = companyWithActiveAdmin("rules", "admin");
        String adminMembership = membershipIdOf(companyId, "admin");
        String managerMembership = addActiveMember(companyId, "manager", "COMPANY_MANAGER");
        String userMembership = addActiveMember(companyId, "user", "COMPANY_USER");

        // Manager cannot invite or create admins, but can invite plain users.
        perform(post("/api/v1/companies/" + companyId + "/invitations"), "manager",
                "{\"email\":\"" + email("x1") + "\",\"roles\":[\"COMPANY_ADMIN\"]}").andExpect(status().isForbidden());
        perform(post("/api/v1/companies/" + companyId + "/invitations"), "manager",
                "{\"email\":\"" + email("x2") + "\",\"roles\":[\"COMPANY_USER\"]}").andExpect(status().isCreated());
        perform(put("/api/v1/companies/" + companyId + "/memberships/" + userMembership + "/roles"), "manager",
                "{\"roles\":[\"COMPANY_ADMIN\"]}").andExpect(status().isForbidden());
        perform(post("/api/v1/companies/" + companyId + "/memberships/" + adminMembership + "/suspend"), "manager", null)
                .andExpect(status().isForbidden());
        // Plain users cannot manage members at all.
        perform(post("/api/v1/companies/" + companyId + "/memberships/" + managerMembership + "/suspend"), "user", null)
                .andExpect(status().isForbidden());

        // Self changes and last admin.
        perform(post("/api/v1/companies/" + companyId + "/memberships/" + adminMembership + "/suspend"), "admin", null)
                .andExpect(status().isConflict());
        perform(post("/api/v1/companies/" + companyId + "/memberships/" + adminMembership + "/suspend"), "root", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        perform(put("/api/v1/companies/" + companyId + "/memberships/" + adminMembership + "/roles"), "root",
                "{\"roles\":[\"COMPANY_USER\"]}").andExpect(jsonPath("$.code").value("LAST_ADMIN"));

        // Promote the manager; now the original admin can be suspended and reactivated.
        perform(put("/api/v1/companies/" + companyId + "/memberships/" + managerMembership + "/roles"), "admin",
                "{\"roles\":[\"COMPANY_ADMIN\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("COMPANY_ADMIN"));
        perform(post("/api/v1/companies/" + companyId + "/memberships/" + adminMembership + "/suspend"), "manager",
                "{\"reason\":\"Leave\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));
        perform(get("/api/v1/companies/" + companyId), "admin", null).andExpect(status().isNotFound());
        perform(post("/api/v1/companies/" + companyId + "/memberships/" + adminMembership + "/reactivate"), "manager", null)
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        perform(put("/api/v1/companies/" + companyId + "/memberships/" + userMembership + "/roles"), "admin",
                "{\"roles\":[\"NOT_A_ROLE\"]}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where company_id = UUID_TO_BIN(?) "
                + "and action in ('membership.roles_changed', 'membership.suspend', 'membership.reactivate')",
                Integer.class, companyId.toString())).isEqualTo(3);
    }

    @Test
    void listsAndSearchesMembers() throws Exception {
        UUID companyId = companyWithActiveAdmin("list", "admin");
        addActiveMember(companyId, "anna", "COMPANY_USER");
        addActiveMember(companyId, "mike", "COMPANY_MANAGER");

        perform(get("/api/v1/companies/" + companyId + "/memberships?sort=email,asc"), "admin", null)
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].email").value(email("admin")));
        perform(get("/api/v1/companies/" + companyId + "/memberships?q=anna"), "admin", null)
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].roles[0]").value("COMPANY_USER"));
        // Short terms filter the company's members; longer ones start from matching users (other companies' "an…" users are not counted).
        perform(get("/api/v1/companies/" + companyId + "/memberships?q=an"), "admin", null)
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].email").value(email("anna")));
        perform(get("/api/v1/companies/" + companyId + "/memberships?role=COMPANY_MANAGER"), "admin", null)
                .andExpect(jsonPath("$.items[0].email").value(email("mike")));
        perform(get("/api/v1/companies/" + companyId + "/memberships?size=1&page=1&sort=email,asc"), "admin", null)
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totalPages").value(3));
        perform(get("/api/v1/companies/" + companyId + "/memberships?sort=password"), "admin", null)
                .andExpect(status().isBadRequest());
        perform(get("/api/v1/companies/" + companyId + "/roles"), "admin", null)
                .andExpect(jsonPath("$.length()").value(3));
        perform(get("/api/v1/users/" + data.userIdByEmail(email("anna")) + "/memberships"), "root", null)
                .andExpect(jsonPath("$[0].companyId").value(companyId.toString()));
        perform(get("/api/v1/users/" + data.userIdByEmail(email("anna")) + "/memberships"), "admin", null)
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- platform users and audit

    @Test
    void platformUserAdministration() throws Exception {
        signIn("frank", false);
        UUID frank = data.userIdByEmail(email("frank"));

        JsonNode first = body(perform(get("/api/v1/users?size=1&q=frank-"), "root", null).andExpect(status().isOk()));
        assertThat(first.get("items").get(0).get("email").asString()).isEqualTo(email("frank"));

        perform(post("/api/v1/users/" + frank + "/disable"), "root", "{\"reason\":\"Left company\"}")
                .andExpect(jsonPath("$.status").value("DISABLED"));
        verify(idp, atLeastOnce()).setEnabled("sub-" + email("frank"), false);
        verify(idp, atLeastOnce()).logout("sub-" + email("frank"));
        perform(get("/api/v1/me"), "frank", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        perform(post("/api/v1/users/" + frank + "/enable"), "root", null).andExpect(jsonPath("$.status").value("ACTIVE"));
        perform(get("/api/v1/me"), "frank", null).andExpect(status().isOk());

        UUID root = data.userIdByEmail(email("root"));
        perform(post("/api/v1/users/" + root + "/disable"), "root", null).andExpect(status().isConflict());
        perform(post("/api/v1/users/" + frank + "/disable"), "frank", null).andExpect(status().isForbidden());

        // Keyset pagination returns disjoint pages.
        JsonNode p1 = body(perform(get("/api/v1/users?size=2"), "root", null));
        JsonNode p2 = body(perform(get("/api/v1/users?size=2&after=" + p1.get("nextCursor").asString()), "root", null));
        assertThat(p2.get("items").get(0).get("id").asString())
                .isNotEqualTo(p1.get("items").get(1).get("id").asString());
    }

    @Test
    void auditLogIsSearchableAndTenantScoped() throws Exception {
        UUID companyA = companyWithActiveAdmin("auda", "admina");
        UUID companyB = companyWithActiveAdmin("audb", "adminb");
        addActiveMember(companyA, "worker", "COMPANY_USER");

        JsonNode page1 = body(perform(get("/api/v1/audit-logs?companyId=" + companyA + "&size=1"), "root", null)
                .andExpect(status().isOk()));
        JsonNode page2 = body(perform(get("/api/v1/audit-logs?companyId=" + companyA + "&size=1&cursor="
                + page1.get("nextCursor").asString()), "root", null));
        assertThat(page2.get("items").get(0).get("id").asString())
                .isNotEqualTo(page1.get("items").get(0).get("id").asString());
        perform(get("/api/v1/audit-logs?action=company.created&companyId=" + companyA), "root", null)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].details.code").value("auda-" + run));

        perform(get("/api/v1/companies/" + companyA + "/audit-logs"), "admina", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].companyId").value(companyA.toString()));
        perform(get("/api/v1/companies/" + companyB + "/audit-logs"), "admina", null).andExpect(status().isNotFound());
        perform(get("/api/v1/companies/" + companyA + "/audit-logs"), "worker", null).andExpect(status().isForbidden());
        perform(get("/api/v1/audit-logs"), "admina", null).andExpect(status().isForbidden());
        perform(get("/api/v1/audit-logs?cursor=garbage"), "root", null).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- helpers

    private String email(String name) {
        return name + "-" + run + "@acme.test";
    }

    private void signIn(String name, boolean mfa) throws Exception {
        tokens.register(name, email(name), mfa);
        perform(get("/api/v1/me"), name, null).andExpect(status().isOk());
    }

    private String companyBody(String code, String adminEmail) {
        return "{\"code\":\"" + code + "\",\"legalName\":\"" + code + " Pvt Ltd\",\"displayName\":\"" + code + "\","
                + "\"country\":\"IN\",\"primaryContact\":{\"name\":\"Owner\",\"email\":\"owner@acme.test\",\"phone\":\"+919876543210\"},"
                + "\"initialAdmin\":{\"email\":\"" + adminEmail + "\",\"firstName\":\"First\",\"lastName\":\"Admin\"}}";
    }

    private JsonNode createCompany(String name) throws Exception {
        return body(perform(post("/api/v1/companies"), "root", companyBody(name + "-" + run, email(name + "-admin")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location")));
    }

    /** Creates a company whose initial admin ({@code adminName}) has accepted the invitation. */
    private UUID companyWithActiveAdmin(String code, String adminName) throws Exception {
        UUID companyId = UUID.fromString(body(perform(post("/api/v1/companies"), "root",
                companyBody(code + "-" + run, email(adminName))).andExpect(status().isCreated())).get("id").asString());
        String token = lastAccountSetupToken();
        signIn(adminName, false);
        accept(adminName, token).andExpect(status().isOk());
        return companyId;
    }

    private String addActiveMember(UUID companyId, String name, String role) throws Exception {
        perform(post("/api/v1/companies/" + companyId + "/invitations"), "root",
                "{\"email\":\"" + email(name) + "\",\"roles\":[\"" + role + "\"]}").andExpect(status().isCreated());
        String token = lastAccountSetupToken();
        signIn(name, false);
        return body(accept(name, token).andExpect(status().isOk())).get("membershipId").asString();
    }

    private String membershipIdOf(UUID companyId, String name) {
        return jdbc.queryForObject("select BIN_TO_UUID(m.id) from company_membership m join platform_user u on u.id = m.user_id "
                + "where m.company_id = UUID_TO_BIN(?) and u.email = ?", String.class, companyId.toString(), email(name));
    }

    private String lastAccountSetupToken() {
        ArgumentCaptor<String> redirect = ArgumentCaptor.forClass(String.class);
        verify(idp, atLeastOnce()).sendActionsEmail(anyString(), any(), any(Duration.class), eq("occpl-admin-portal"),
                redirect.capture());
        List<String> all = redirect.getAllValues();
        String url = all.getLast();
        assertThat(url).startsWith("http://localhost:4200/invitations/accept?token=");
        return URLDecoder.decode(url.substring(url.indexOf("token=") + 6), StandardCharsets.UTF_8);
    }

    private String invitationStatus(String id) {
        return jdbc.queryForObject("select status from invitation where id = UUID_TO_BIN(?)", String.class, id);
    }

    private ResultActions accept(String user, String token) throws Exception {
        return perform(post("/api/v1/invitations/accept"), user, "{\"token\":\"" + token + "\"}");
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
