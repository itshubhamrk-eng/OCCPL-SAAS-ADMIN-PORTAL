package com.occpl.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.occpl.platform.entitlement.api.EntitlementStatus;
import com.occpl.platform.entitlement.api.Entitlements;
import com.occpl.platform.entitlement.api.Entitlements.Reason;
import com.occpl.platform.identity.api.IdentityProviderAdmin;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.subscription.internal.SubscriptionSweeper;
import com.occpl.platform.support.MockTokens;
import com.occpl.platform.support.MySqlTestDatabase;
import com.occpl.platform.support.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Plans, subscription lifecycle (incl. timed sweeps), entitlements, usage limits and seat limits. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class SubscriptionsAndEntitlementsIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired Entitlements entitlements;
    @Autowired SubscriptionSweeper sweeper;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean IdentityProviderAdmin idp;

    MockTokens tokens;
    TestData data;
    String run;
    String productId;
    String productCode;

    @BeforeEach
    void setUp() throws Exception {
        tokens = new MockTokens(jwtDecoder);
        data = new TestData(jdbc);
        run = UUID.randomUUID().toString().substring(0, 6);
        given(idp.findByEmail(anyString())).willReturn(Optional.empty());
        given(idp.createUser(anyString(), any(), any(), any())).willAnswer(i -> "kc-" + UUID.randomUUID());
        signIn("root", true);
        data.platformRole(data.userIdByEmail(email("root")), TestData.SUPER_ADMIN_ROLE);
        productCode = "crm-" + run;
        productId = activeProduct(productCode);
    }

    // ------------------------------------------------------------------ plans

    @Test
    void plansAreValidatedPublishedFrozenAndCopied() throws Exception {
        perform(post("/api/v1/plans"), "root", planBody("bad-" + run, """
                [{"currency":"XYZ","amountMinor":100},{"currency":"INR","amountMinor":1},{"currency":"inr","amountMinor":2}]""",
                "[{\"code\":\"nope\"},{\"code\":\"export\",\"limit\":5},{\"code\":\"api.calls\",\"limit\":-1}]"))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/plans"), "root", planBody("bad2-" + run, "[{\"currency\":\"XYZ\",\"amountMinor\":100},"
                + "{\"currency\":\"INR\",\"amountMinor\":1},{\"currency\":\"inr\",\"amountMinor\":2}]",
                "[{\"code\":\"nope\"},{\"code\":\"export\",\"limit\":5}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(4));

        String draftProduct = body(perform(post("/api/v1/products"), "root",
                "{\"code\":\"draft-" + run + "\",\"name\":\"Draft\",\"appUrl\":\"https://d.occpl.test\"}")).get("id").asString();
        String planId = createPlan("basic-" + run, 1000, 5L, 0);
        String withDraft = body(perform(post("/api/v1/plans"), "root", "{\"code\":\"withdraft-" + run + "\",\"plan\":{"
                + "\"name\":\"X\",\"billingPeriod\":\"MONTHLY\",\"trialDays\":0,\"prices\":[{\"currency\":\"INR\",\"amountMinor\":1}],"
                + "\"products\":[{\"productId\":\"" + draftProduct + "\",\"features\":[]}]}}")).get("id").asString();
        perform(post("/api/v1/plans/" + withDraft + "/publish"), "root", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("inactiveProduct"));

        perform(post("/api/v1/plans/" + planId + "/publish"), "root", null).andExpect(jsonPath("$.status").value("ACTIVE"));
        perform(put("/api/v1/plans/" + planId), "root", planContent(1, 1L, 0)).andExpect(status().isConflict());
        perform(get("/api/v1/plans/" + planId), "root", null)
                .andExpect(jsonPath("$.products[0].features[0].code").value("api.calls"))
                .andExpect(jsonPath("$.products[0].features[0].limit").value(1000))
                .andExpect(jsonPath("$.prices[0].amountMinor").value(1000));

        String copy = body(perform(post("/api/v1/plans/" + planId + "/copy"), "root",
                "{\"code\":\"basic-v2-" + run + "\",\"name\":\"Basic v2\"}").andExpect(status().isCreated())).get("id").asString();
        perform(get("/api/v1/plans/" + copy), "root", null).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.products[0].features.length()").value(3));
        perform(post("/api/v1/plans/" + planId + "/archive"), "root", null).andExpect(jsonPath("$.status").value("ARCHIVED"));
        perform(post("/api/v1/plans/" + planId + "/archive"), "root", null).andExpect(status().isUnprocessableContent());
    }

    // ------------------------------------------------------------------ subscriptions

    @Test
    void assignmentRulesAndTrialEntitlements() throws Exception {
        UUID company = data.company("c-" + run, "ACTIVE");
        String plan = publishedPlan("trial-" + run, 1000, 5L, 14);
        String archived = publishedPlan("old-" + run, 500, 5L, 0);
        perform(post("/api/v1/plans/" + archived + "/archive"), "root", null);

        assign(company, archived, "INR", false).andExpect(status().isConflict());
        assign(company, plan, "USD", false).andExpect(status().isBadRequest());
        String sub = body(assign(company, plan, "INR", true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.history[0].event").value("CREATED"))).get("id").asString();
        assign(company, plan, "INR", false).andExpect(status().isConflict()); // one live subscription per plan

        perform(get("/api/v1/companies/" + company + "/entitlements"), "root", null)
                .andExpect(jsonPath("$[0].productCode").value(productCode))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].features[?(@.code=='seats')].limit").value(5));
        assertThat(entitlements.check(company, productCode, "export").allowed()).isTrue();
        assertThat(entitlements.check(company, productCode, null).allowed()).isTrue();
        assertThat(entitlements.check(company, "unknown-product", null).reason()).isEqualTo(Reason.NO_ENTITLEMENT);

        perform(post("/api/v1/companies/" + company + "/subscriptions/" + sub + "/activate"), "root", null)
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.trialEnd").doesNotExist());
        perform(post("/api/v1/companies/" + company + "/subscriptions/" + sub + "/activate"), "root", null)
                .andExpect(status().isUnprocessableContent());

        jdbc.update("update company set status = 'SUSPENDED' where id = UUID_TO_BIN(?)", company.toString());
        assertThat(entitlements.check(company, productCode, null).reason()).isEqualTo(Reason.COMPANY_INACTIVE);
    }

    @Test
    void planChangesRenewalAndCancellation() throws Exception {
        UUID company = data.company("pc-" + run, "ACTIVE");
        String basic = publishedPlan("b-" + run, 1000, 5L, 0);
        String pro = publishedPlan("p-" + run, 5000, 50L, 0);
        String base = "/api/v1/companies/" + company + "/subscriptions/";
        String sub = body(assign(company, basic, "INR", false)).get("id").asString();

        perform(post(base + sub + "/change-plan"), "root", "{\"planId\":\"" + pro + "\"}")
                .andExpect(jsonPath("$.planId").value(pro)).andExpect(jsonPath("$.priceMinor").value(5000));
        assertThat(entitlements.memberLimit(company)).hasValue(50);

        perform(post(base + sub + "/change-plan"), "root", "{\"planId\":\"" + basic + "\"}")
                .andExpect(jsonPath("$.planId").value(pro)).andExpect(jsonPath("$.pendingPlanId").value(basic));
        JsonNode renewed = body(perform(post(base + sub + "/renew"), "root", null));
        assertThat(renewed.get("planId").asString()).isEqualTo(basic);
        assertThat(renewed.get("pendingPlanId").isNull()).isTrue();
        assertThat(entitlements.memberLimit(company)).hasValue(5);

        perform(post(base + sub + "/cancel"), "root", "{\"mode\":\"PERIOD_END\",\"reason\":\"Budget\"}")
                .andExpect(jsonPath("$.cancelAtPeriodEnd").value(true)).andExpect(jsonPath("$.status").value("ACTIVE"));
        perform(post(base + sub + "/resume"), "root", null).andExpect(jsonPath("$.cancelAtPeriodEnd").value(false));
        JsonNode cancelled = body(perform(post(base + sub + "/cancel"), "root", "{\"mode\":\"IMMEDIATE\"}")
                .andExpect(jsonPath("$.status").value("CANCELLED")));
        Instant retention = Instant.parse(cancelled.get("dataRetentionUntil").asString());
        assertThat(Duration.between(Instant.now(), retention).toDays()).isBetween(89L, 90L);
        assertThat(entitlements.check(company, productCode, null).reason()).isEqualTo(Reason.NO_ENTITLEMENT);
        perform(post(base + sub + "/renew"), "root", null).andExpect(status().isUnprocessableContent());

        JsonNode detail = body(perform(get(base + sub), "root", null));
        assertThat(detail.get("history").valueStream().map(h -> h.get("event").asString()).toList())
                .containsExactly("CREATED", "UPGRADED", "DOWNGRADE_SCHEDULED", "RENEWED_WITH_DOWNGRADE",
                        "CANCELLATION_SCHEDULED", "CANCELLATION_REVOKED", "CANCELLED");
    }

    @Test
    void timedLifecycleSweepsAreIdempotent() throws Exception {
        String plan = publishedPlan("sw-" + run, 1000, 5L, 14);
        UUID trialCo = data.company("t-" + run, "ACTIVE");
        UUID dueCo = data.company("d-" + run, "ACTIVE");
        UUID cancelCo = data.company("x-" + run, "ACTIVE");
        String trial = body(assign(trialCo, plan, "INR", true)).get("id").asString();
        String due = body(assign(dueCo, plan, "INR", false)).get("id").asString();
        String cancelling = body(assign(cancelCo, plan, "INR", false)).get("id").asString();
        perform(post("/api/v1/companies/" + cancelCo + "/subscriptions/" + cancelling + "/cancel"), "root",
                "{\"mode\":\"PERIOD_END\"}");

        moveIntoPast(trial, "trial_end", Duration.ofMinutes(1));
        moveIntoPast(due, "current_period_end", Duration.ofMinutes(1));
        moveIntoPast(cancelling, "current_period_end", Duration.ofMinutes(1));
        sweeper.sweep();
        assertThat(subscriptionStatus(trial)).isEqualTo("EXPIRED");
        assertThat(subscriptionStatus(due)).isEqualTo("PAST_DUE");
        assertThat(subscriptionStatus(cancelling)).isEqualTo("EXPIRED");
        assertThat(entitlements.check(dueCo, productCode, null)).satisfies(d -> {
            assertThat(d.allowed()).isTrue();
            assertThat(d.status()).isEqualTo(EntitlementStatus.GRACE);
        });

        moveIntoPast(due, "current_period_end", Duration.ofDays(8)); // grace (7 days) elapsed
        sweeper.sweep();
        assertThat(subscriptionStatus(due)).isEqualTo("SUSPENDED");
        assertThat(entitlements.check(dueCo, productCode, null).reason()).isEqualTo(Reason.SUSPENDED);
        perform(get("/api/v1/companies/" + dueCo + "/subscriptions/" + due), "root", null)
                .andExpect(jsonPath("$.history[2].reason").value("Grace period elapsed"));

        moveIntoPast(due, "status_changed_at", Duration.ofDays(91));
        sweeper.sweep();
        assertThat(subscriptionStatus(due)).isEqualTo("EXPIRED");
        assertThat(sweeper.sweep()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where target_id = ? and actor_type = 'SYSTEM'",
                Integer.class, due)).isEqualTo(3);
    }

    @Test
    void renewalRecoversPastDueAndSuspendedSubscriptions() throws Exception {
        UUID company = data.company("r-" + run, "ACTIVE");
        String plan = publishedPlan("rn-" + run, 1000, 5L, 0);
        String sub = body(assign(company, plan, "INR", false)).get("id").asString();
        moveIntoPast(sub, "current_period_end", Duration.ofDays(9));
        sweeper.sweep();
        sweeper.sweep();
        assertThat(subscriptionStatus(sub)).isEqualTo("SUSPENDED");
        perform(post("/api/v1/companies/" + company + "/subscriptions/" + sub + "/renew"), "root", null)
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(entitlements.check(company, productCode, null).status()).isEqualTo(EntitlementStatus.ACTIVE);
    }

    @Test
    void companyMembersSeeOnlyTheirOwnSubscriptions() throws Exception {
        UUID mine = data.company("m-" + run, "ACTIVE");
        UUID other = data.company("o-" + run, "ACTIVE");
        String plan = publishedPlan("vis-" + run, 1000, 5L, 0);
        assign(mine, plan, "INR", false);
        assign(other, plan, "INR", false);
        signIn("member", false);
        data.membership(mine, data.userIdByEmail(email("member")), "ACTIVE", TestData.COMPANY_ADMIN_ROLE);

        perform(get("/api/v1/companies/" + mine + "/subscriptions"), "member", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        perform(get("/api/v1/companies/" + mine + "/entitlements"), "member", null).andExpect(status().isOk());
        perform(get("/api/v1/companies/" + other + "/subscriptions"), "member", null).andExpect(status().isNotFound());
        assign(mine, plan, "INR", false, "member").andExpect(status().isForbidden());
        perform(get("/api/v1/platform/subscriptions?companyId=" + mine), "root", null)
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    // ------------------------------------------------------------------ entitlements and usage

    @Test
    void featureLimitsMergeAcrossSubscriptions() throws Exception {
        UUID company = data.company("mg-" + run, "ACTIVE");
        assign(company, publishedPlan("s5-" + run, 1000, 5L, 0), "INR", false);
        assign(company, publishedPlan("s20-" + run, 2000, 20L, 0), "INR", false);
        assertThat(entitlements.memberLimit(company)).hasValue(20);
        assign(company, publishedPlan("unl-" + run, 3000, null, 0), "INR", false);
        assertThat(entitlements.memberLimit(company)).isEmpty();
    }

    @Test
    void usageIsAtomicIdempotentAndLimited() throws Exception {
        UUID company = data.company("u-" + run, "ACTIVE");
        assign(company, publishedPlan("use-" + run, 1000, 5L, 0), "INR", false);

        Entitlements.UsageResult first = entitlements.consume(company, productCode, "api.calls", 600, "req-1");
        assertThat(first.used()).isEqualTo(600);
        assertThat(entitlements.consume(company, productCode, "api.calls", 600, "req-1").replayed()).isTrue();
        assertThatThrownBy(() -> entitlements.consume(company, productCode, "api.calls", 401, "req-2"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
        assertThat(entitlements.consume(company, productCode, "api.calls", 400, "req-3").remaining()).isZero();
        assertThat(entitlements.check(company, productCode, "api.calls").reason()).isEqualTo(Reason.LIMIT_REACHED);
        assertThat(entitlements.release(company, productCode, "api.calls", 100, "req-4").used()).isEqualTo(900);
        assertThatThrownBy(() -> entitlements.consume(company, productCode, "export", 1, "req-5"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        perform(get("/api/v1/companies/" + company + "/entitlements"), "root", null)
                .andExpect(jsonPath("$[0].features[?(@.code=='api.calls')].used").value(900));
    }

    @Test
    void concurrentConsumersNeverExceedTheLimit() throws Exception {
        UUID company = data.company("cc-" + run, "ACTIVE");
        assign(company, publishedPlan("con-" + run, 1000, 5L, 0), "INR", false);
        jdbc.update("""
                update entitlement_feature ef join company_product_entitlement e on e.id = ef.entitlement_id
                join product_feature f on f.id = ef.feature_id
                set ef.limit_value = 50 where e.company_id = UUID_TO_BIN(?) and f.code = 'api.calls'""", company.toString());

        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            String key = "c-" + i;
            calls.add(() -> {
                try {
                    entitlements.consume(company, productCode, "api.calls", 1, key);
                    return true;
                } catch (ApiException e) {
                    return false;
                }
            });
        }
        int succeeded = 0;
        for (Future<Boolean> f : pool.invokeAll(calls)) {
            succeeded += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(succeeded).isEqualTo(50);
        assertThat(entitlements.check(company, productCode, "api.calls").used()).isEqualTo(50);
        assertThat(jdbc.queryForObject("select count(*) from usage_record where company_id = UUID_TO_BIN(?)",
                Integer.class, company.toString())).isEqualTo(50);
    }

    @Test
    void seatLimitBlocksInvitationsAndReactivation() throws Exception {
        UUID company = data.company("seat-" + run, "ACTIVE");
        assign(company, publishedPlan("two-" + run, 1000, 2L, 0), "INR", false);
        signIn("owner", false);
        data.membership(company, data.userIdByEmail(email("owner")), "ACTIVE", TestData.COMPANY_ADMIN_ROLE);

        JsonNode first = body(invite(company, "first").andExpect(status().isCreated()));
        invite(company, "second").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));

        // Deactivating an invited member frees the seat and revokes the pending invitation, so neither
        // accepting nor re-sending it can bring the member back on top of the new one.
        String companyPath = "/api/v1/companies/" + company;
        perform(post(companyPath + "/memberships/" + first.get("membershipId").asString() + "/deactivate"), "owner", "{}")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from invitation where id = UUID_TO_BIN(?)", String.class,
                first.get("id").asString())).isEqualTo("REVOKED");
        invite(company, "second").andExpect(status().isCreated());
        perform(post(companyPath + "/invitations/" + first.get("id").asString() + "/resend"), "owner", null)
                .andExpect(status().isUnprocessableContent());
        // An expired invitation of a deactivated member can be re-sent only if a seat is free.
        jdbc.update("update invitation set status = 'EXPIRED' where id = UUID_TO_BIN(?)", first.get("id").asString());
        perform(post(companyPath + "/invitations/" + first.get("id").asString() + "/resend"), "owner", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));

        signIn("parked", false);
        String parked = data.membership(company, data.userIdByEmail(email("parked")), "SUSPENDED",
                TestData.COMPANY_USER_ROLE).toString();
        perform(post("/api/v1/companies/" + company + "/memberships/" + parked + "/reactivate"), "owner", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));
    }

    // ------------------------------------------------------------------ helpers

    private String email(String name) {
        return name + "-" + run + "@occpl.test";
    }

    private void signIn(String name, boolean mfa) throws Exception {
        tokens.register(name, email(name), mfa);
        perform(get("/api/v1/me"), name, null).andExpect(status().isOk());
    }

    private String activeProduct(String code) throws Exception {
        String id = body(perform(post("/api/v1/products"), "root",
                "{\"code\":\"" + code + "\",\"name\":\"CRM\",\"appUrl\":\"https://" + code + ".occpl.test\"}")).get("id").asString();
        String base = "/api/v1/products/" + id;
        perform(post(base + "/features"), "root", "{\"code\":\"seats\",\"name\":\"Seats\",\"type\":\"LIMIT\",\"unit\":\"SEATS\",\"resetPeriod\":\"NONE\"}");
        perform(post(base + "/features"), "root", "{\"code\":\"api.calls\",\"name\":\"API calls\",\"type\":\"LIMIT\",\"unit\":\"API_CALLS\",\"resetPeriod\":\"MONTHLY\"}");
        perform(post(base + "/features"), "root", "{\"code\":\"export\",\"name\":\"Export\",\"type\":\"BOOLEAN\"}");
        perform(post(base + "/versions"), "root", "{\"config\":{\"schemaVersion\":1,\"provisioning\":{\"mode\":\"NONE\"}}}");
        perform(post(base + "/versions/1/publish"), "root", null);
        perform(post(base + "/activate"), "root", null).andExpect(status().isOk());
        return id;
    }

    private String planContent(long price, Long seats, int trialDays) {
        return "{\"name\":\"Plan\",\"billingPeriod\":\"MONTHLY\",\"trialDays\":" + trialDays + ","
                + "\"prices\":[{\"currency\":\"INR\",\"amountMinor\":" + price + "}],"
                + "\"products\":[{\"productId\":\"" + productId + "\",\"features\":["
                + "{\"code\":\"seats\"" + (seats == null ? "" : ",\"limit\":" + seats) + "},"
                + "{\"code\":\"api.calls\",\"limit\":1000},{\"code\":\"export\"}]}]}";
    }

    private String planBody(String code, String prices, String features) {
        return "{\"code\":\"" + code + "\",\"plan\":{\"name\":\"Bad\",\"billingPeriod\":\"MONTHLY\",\"trialDays\":0,"
                + "\"prices\":" + prices + ",\"products\":[{\"productId\":\"" + productId + "\",\"features\":" + features + "}]}}";
    }

    private String createPlan(String code, long price, Long seats, int trialDays) throws Exception {
        return body(perform(post("/api/v1/plans"), "root", "{\"code\":\"" + code + "\",\"plan\":"
                + planContent(price, seats, trialDays) + "}").andExpect(status().isCreated())).get("id").asString();
    }

    private String publishedPlan(String code, long price, Long seats, int trialDays) throws Exception {
        String id = createPlan(code, price, seats, trialDays);
        perform(post("/api/v1/plans/" + id + "/publish"), "root", null).andExpect(status().isOk());
        return id;
    }

    private ResultActions assign(UUID company, String plan, String currency, boolean trial) throws Exception {
        return assign(company, plan, currency, trial, "root");
    }

    private ResultActions assign(UUID company, String plan, String currency, boolean trial, String as) throws Exception {
        return perform(post("/api/v1/companies/" + company + "/subscriptions"), as,
                "{\"planId\":\"" + plan + "\",\"currency\":\"" + currency + "\",\"startTrial\":" + trial + "}");
    }

    private ResultActions invite(UUID company, String name) throws Exception {
        return perform(post("/api/v1/companies/" + company + "/invitations"), "owner",
                "{\"email\":\"" + email(name) + "\",\"roles\":[\"COMPANY_USER\"]}");
    }

    private void moveIntoPast(String subscriptionId, String column, Duration ago) {
        jdbc.update("update company_subscription set " + column + " = UTC_TIMESTAMP(6) - interval ? second "
                + "where id = UUID_TO_BIN(?)", ago.toSeconds(), subscriptionId);
    }

    private String subscriptionStatus(String subscriptionId) {
        return jdbc.queryForObject("select status from company_subscription where id = UUID_TO_BIN(?)", String.class,
                subscriptionId);
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
