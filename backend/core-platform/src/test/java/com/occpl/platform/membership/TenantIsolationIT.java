package com.occpl.platform.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.occpl.platform.membership.api.TenantContext;
import com.occpl.platform.support.MySqlTestDatabase;
import com.occpl.platform.support.TestData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant isolation and authorization against the real database. Tokens are decoded by a mock so
 * each test controls the claims; everything after decoding (user mapping, roles, membership checks)
 * is the production code path.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TenantIsolationIT.ProbeEndpoints.class)
class TenantIsolationIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    TestData data;
    UUID companyA;
    UUID companyB;
    String run;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        run = UUID.randomUUID().toString().substring(0, 8);
        companyA = data.company("a-" + run, "ACTIVE");
        companyB = data.company("b-" + run, "ACTIVE");
        willThrow(new BadJwtException("unknown test token")).given(jwtDecoder).decode(anyString());
    }

    /** Registers a token whose subject is linked to a fresh user; returns the user id. */
    private UUID user(String token, boolean mfa) {
        String email = token + "@" + run + ".test";
        UUID userId = data.user(email, "ACTIVE");
        data.identity(userId, "sub-" + token + "-" + run);
        token(token, "sub-" + token + "-" + run, email, true, mfa);
        return userId;
    }

    private void token(String token, String subject, String email, boolean emailVerified, boolean mfa) {
        Jwt.Builder jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(subject)
                .claim("email", email).claim("email_verified", emailVerified).claim("name", "Test " + token)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        jwt.claim("amr", mfa ? List.of("pwd", "otp") : List.of("pwd"));
        willReturn(jwt.build()).given(jwtDecoder).decode(token);
    }

    @Test
    void memberCanUseOwnCompanyWithinRolePermissions() throws Exception {
        UUID alice = user("alice", false);
        data.membership(companyA, alice, "ACTIVE", TestData.COMPANY_USER_ROLE);

        mvc.perform(get("/test/companies/{id}/profile", companyA).header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(companyA.toString()))
                .andExpect(jsonPath("$.membership").value(true));
    }

    @Test
    void otherCompaniesAreIndistinguishableFromMissingOnes() throws Exception {
        UUID alice = user("alice", false);
        data.membership(companyA, alice, "ACTIVE", TestData.COMPANY_ADMIN_ROLE);

        var foreign = mvc.perform(get("/test/companies/{id}/profile", companyB).header("Authorization", "Bearer alice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        var missing = mvc.perform(get("/test/companies/{id}/profile", UUID.randomUUID())
                        .header("Authorization", "Bearer alice"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(stripVolatile(foreign)).isEqualTo(stripVolatile(missing));
    }

    @Test
    void permissionOutsideRoleIsForbidden() throws Exception {
        UUID alice = user("alice", false);
        data.membership(companyA, alice, "ACTIVE", TestData.COMPANY_USER_ROLE);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/test/companies/{id}/users", companyA).header("Authorization", "Bearer alice"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void suspendedOrDeactivatedMembershipsHaveNoAccess() throws Exception {
        UUID alice = user("alice", false);
        data.membership(companyA, alice, "SUSPENDED", TestData.COMPANY_ADMIN_ROLE);
        UUID bob = user("bob", false);
        data.membership(companyA, bob, "DEACTIVATED", TestData.COMPANY_ADMIN_ROLE);

        mvc.perform(get("/test/companies/{id}/profile", companyA).header("Authorization", "Bearer alice"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/test/companies/{id}/profile", companyA).header("Authorization", "Bearer bob"))
                .andExpect(status().isNotFound());
    }

    @Test
    void suspendedCompanyIsReadOnlyAndDeactivatedCompanyIsGone() throws Exception {
        UUID suspended = data.company("s-" + run, "SUSPENDED");
        UUID deactivated = data.company("d-" + run, "DEACTIVATED");
        UUID alice = user("alice", false);
        data.membership(suspended, alice, "ACTIVE", TestData.COMPANY_ADMIN_ROLE);
        data.membership(deactivated, alice, "ACTIVE", TestData.COMPANY_ADMIN_ROLE);

        mvc.perform(get("/test/companies/{id}/profile", suspended).header("Authorization", "Bearer alice"))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/test/companies/{id}/users", suspended).header("Authorization", "Bearer alice"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_SUSPENDED"));
        mvc.perform(get("/test/companies/{id}/profile", deactivated).header("Authorization", "Bearer alice"))
                .andExpect(status().isNotFound());
    }

    @Test
    void platformStaffNeedMfaToActOnCompanies() throws Exception {
        UUID support = user("support", true);
        data.platformRole(support, UUID.fromString("01920000-0000-7000-8000-000000000002"));
        UUID supportNoMfa = user("supportnomfa", false);
        data.platformRole(supportNoMfa, UUID.fromString("01920000-0000-7000-8000-000000000002"));

        mvc.perform(get("/test/companies/{id}/profile", companyB).header("Authorization", "Bearer support"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membership").value(false));
        mvc.perform(get("/test/companies/{id}/profile", companyB).header("Authorization", "Bearer supportnomfa"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer supportnomfa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform.roles[0]").value("PLATFORM_SUPPORT"))
                .andExpect(jsonPath("$.platform.permissions").isEmpty())
                .andExpect(jsonPath("$.platform.mfaRequired").value(true));
    }

    @Test
    void meListsActiveMembershipsWithRolesAndPermissions() throws Exception {
        UUID alice = user("alice", false);
        data.membership(companyA, alice, "ACTIVE", TestData.COMPANY_MANAGER_ROLE);
        data.membership(companyB, alice, "SUSPENDED", TestData.COMPANY_ADMIN_ROLE);

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.toString()))
                .andExpect(jsonPath("$.memberships.length()").value(1))
                .andExpect(jsonPath("$.memberships[0].companyId").value(companyA.toString()))
                .andExpect(jsonPath("$.memberships[0].roles[0]").value("COMPANY_MANAGER"))
                .andExpect(jsonPath("$.memberships[0].permissions.length()").value(7))
                .andExpect(jsonPath("$.platform.roles").isEmpty());
    }

    @Test
    void firstLoginCreatesUserOnlyForVerifiedEmail() throws Exception {
        token("new-unverified", "sub-nu-" + run, "nu-" + run + "@x.test", false, false);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer new-unverified"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        token("new-verified", "sub-nv-" + run, "NV-" + run + "@X.test", true, false);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer new-verified"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("nv-" + run + "@x.test"))
                .andExpect(jsonPath("$.displayName").value("Test new-verified"));
    }

    @Test
    void firstLoginLinksInvitedUserAndActivatesIt() throws Exception {
        String email = "invited-" + run + "@x.test";
        UUID invited = data.user(email, "INVITED");
        token("invitee", "sub-inv-" + run, email, true, false);

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer invitee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(invited.toString()));
        assertThat(jdbc.queryForObject("select status from platform_user where email = ?", String.class, email))
                .isEqualTo("ACTIVE");
    }

    @Test
    void emailAlreadyLinkedToAnotherSubjectIsNotTakenOver() throws Exception {
        String email = "owner-" + run + "@x.test";
        UUID owner = data.user(email, "ACTIVE");
        data.identity(owner, "original-sub-" + run);
        token("impostor", "other-sub-" + run, email, true, false);

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer impostor"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_CONFLICT"));
    }

    @Test
    void disabledUsersAreRejected() throws Exception {
        UUID carol = user("carol", false);
        jdbc.update("update platform_user set status = 'DISABLED' where id = UUID_TO_BIN(?)", carol.toString());

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer carol"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void concurrentFirstLoginsCreateExactlyOneUser() throws Exception {
        String email = "race-" + run + "@x.test";
        token("racer", "sub-race-" + run, email, true, false);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(() -> mvc.perform(get("/api/v1/me").header("Authorization", "Bearer racer"))
                        .andReturn().getResponse().getStatus());
            }
            for (Future<Integer> result : pool.invokeAll(calls)) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("select count(*) from platform_user where email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    @Test
    void platformRoleManagementRequiresPermissionAndProtectsLastSuperAdmin() throws Exception {
        jdbc.update("delete from platform_role_assignment where role_id = UUID_TO_BIN(?)",
                TestData.SUPER_ADMIN_ROLE.toString());
        UUID admin = user("admin", true);
        data.platformRole(admin, TestData.SUPER_ADMIN_ROLE);
        UUID target = user("target", false);
        user("nobody", false);

        mvc.perform(put("/api/v1/platform/users/{id}/roles", target).header("Authorization", "Bearer nobody")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"SUPER_ADMIN\"]}"))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/platform/users/{id}/roles", target).header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"PLATFORM_SUPPORT\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("PLATFORM_SUPPORT"));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'platform.user.roles_changed' "
                + "and target_id = ? and actor_user_id = UUID_TO_BIN(?)", Integer.class,
                target.toString(), admin.toString())).isEqualTo(1);

        mvc.perform(put("/api/v1/platform/users/{id}/roles", target).header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"COMPANY_ADMIN\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(put("/api/v1/platform/users/{id}/roles", admin).header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));

        mvc.perform(put("/api/v1/platform/users/{id}/roles", UUID.randomUUID()).header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void profileUpdateValidatesInput() throws Exception {
        user("dave", false);
        mvc.perform(get("/api/v1/me/profile").header("Authorization", "Bearer dave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").doesNotExist());
        mvc.perform(put("/api/v1/me/profile").header("Authorization", "Bearer dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"\",\"phone\":\"12\",\"timeZone\":\"Mars/Base\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));
        mvc.perform(put("/api/v1/me/profile").header("Authorization", "Bearer dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Dave K\",\"phone\":\"+919876543210\",\"locale\":\"en-IN\","
                                + "\"timeZone\":\"Asia/Kolkata\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Dave K"))
                .andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"));
        mvc.perform(get("/api/v1/me/profile").header("Authorization", "Bearer dave"))
                .andExpect(jsonPath("$.phone").value("+919876543210"))
                .andExpect(jsonPath("$.locale").value("en-IN"));
    }

    @Test
    void permissionCatalogueIsAvailableToAuthenticatedUsers() throws Exception {
        user("erin", false);
        mvc.perform(get("/api/v1/permissions?scope=COMPANY").header("Authorization", "Bearer erin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12));
        mvc.perform(get("/api/v1/permissions")).andExpect(status().isUnauthorized());
    }

    private static String stripVolatile(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "").replaceAll("\"instance\":\"[^\"]*\"", "");
    }

    /** Company-scoped endpoints used to exercise {@code @tenant}; real ones arrive in Phase 4. */
    @TestConfiguration
    static class ProbeEndpoints {

        @Bean
        ProbeController tenantProbeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/test/companies/{companyId}/profile")
        @PreAuthorize("@tenant.check(#companyId, 'company.profile.read', 'platform.companies.read')")
        Map<String, Object> profile(@PathVariable UUID companyId) {
            TenantContext context = TenantContext.current().orElseThrow();
            return Map.of("companyId", context.companyId(), "membership", context.membershipId() != null);
        }

        @PostMapping("/test/companies/{companyId}/users")
        @PreAuthorize("@tenant.check(#companyId, 'company.users.manage')")
        Map<String, Object> manageUsers(@PathVariable UUID companyId) {
            return Map.of("ok", true);
        }
    }
}
