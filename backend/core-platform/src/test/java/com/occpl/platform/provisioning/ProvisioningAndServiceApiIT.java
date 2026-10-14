package com.occpl.platform.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.occpl.platform.identity.api.IdentityProviderAdmin;
import com.occpl.platform.provisioning.internal.ProvisioningRunner;
import com.occpl.platform.support.MockTokens;
import com.occpl.platform.support.MySqlTestDatabase;
import com.occpl.platform.support.ProductServiceStub;
import com.occpl.platform.support.ProductServiceStub.Response;
import com.occpl.platform.support.TestData;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
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

/**
 * Service tokens and the service API, and provisioning against a fake product service: SYNC, ASYNC
 * with callbacks, retries with back-off, permanent failures with alerts, timeouts, manual retry,
 * delayed deprovisioning and its cancellation, and the company product launcher.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class ProvisioningAndServiceApiIT {

    static final ProductServiceStub stub;

    static {
        try {
            stub = new ProductServiceStub();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
        registry.add("occpl.provisioning.token-uri", stub::tokenUri);
        registry.add("occpl.provisioning.client-secret", () -> "test-secret");
        registry.add("occpl.provisioning.alert-recipients", () -> "ops@occpl.test");
        registry.add("occpl.provisioning.callback-base-url", () -> "https://core.occpl.test");
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired ProvisioningRunner runner;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean IdentityProviderAdmin idp;

    MockTokens tokens;
    TestData data;
    String run;

    @BeforeEach
    void setUp() throws Exception {
        tokens = new MockTokens(jwtDecoder);
        data = new TestData(jdbc);
        run = UUID.randomUUID().toString().substring(0, 6);
        given(idp.findByEmail(anyString())).willReturn(Optional.empty());
        given(idp.createUser(anyString(), any(), any(), any())).willAnswer(i -> "kc-" + UUID.randomUUID());
        tokens.register("root", email("root"), true);
        perform(get("/api/v1/me"), "root", null).andExpect(status().isOk());
        data.platformRole(data.userIdByEmail(email("root")), TestData.SUPER_ADMIN_ROLE);
    }

    // ------------------------------------------------------------------ service tokens and service API

    @Test
    void serviceTokensAreLimitedToTheServiceApiOfTheirOwnProduct() throws Exception {
        Product product = product("svc", "NONE");
        UUID company = data.company("sa-" + run, "ACTIVE");
        UUID other = data.company("sb-" + run, "ACTIVE");
        subscribe(company, plan(product, 2));

        UUID owner = member(company, "owner", TestData.COMPANY_USER_ROLE);
        UUID manager = member(company, "mgr", TestData.COMPANY_MANAGER_ROLE);
        data.user(email("outsider"), "ACTIVE");
        data.identity(data.userIdByEmail(email("outsider")), "sub-" + email("outsider"));
        UUID suspended = member(company, "susp", TestData.COMPANY_USER_ROLE);
        jdbc.update("update company_membership set status = 'SUSPENDED' where user_id = UUID_TO_BIN(?)",
                suspended.toString());

        tokens.registerService("svc-token", product.clientId);
        tokens.registerService("rogue-token", "unknown-client-" + run);

        // a service token cannot use user endpoints, a user token cannot use the service API
        perform(get("/api/v1/me"), "svc-token", null).andExpect(status().isForbidden());
        perform(get("/api/v1/companies/" + company + "/products"), "svc-token", null).andExpect(status().isForbidden());
        perform(get("/api/v1/service/access?companyId=" + company), "root", null).andExpect(status().isForbidden());
        perform(get("/api/v1/service/access?companyId=" + company), "rogue-token", null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/service/access?companyId=" + company)).andExpect(status().isUnauthorized());

        access("svc-token", company, "sub-" + email("owner"), "export")
                .andExpect(jsonPath("$.allowed").value(true)).andExpect(jsonPath("$.reason").value("OK"))
                .andExpect(jsonPath("$.userId").value(owner.toString()))
                .andExpect(jsonPath("$.roles[0]").value("COMPANY_USER"))
                .andExpect(jsonPath("$.entitlementStatus").value("ACTIVE"));
        access("svc-token", company, "sub-" + email("mgr"), "notes")
                .andExpect(jsonPath("$.allowed").value(true)).andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.used").value(0));
        assertThat(manager).isNotNull();
        access("svc-token", company, "sub-" + email("outsider"), null).andExpect(jsonPath("$.reason").value("NOT_A_MEMBER"));
        access("svc-token", company, "sub-" + email("susp"), null).andExpect(jsonPath("$.reason").value("NOT_A_MEMBER"));
        access("svc-token", company, "sub-nobody", null).andExpect(jsonPath("$.reason").value("UNKNOWN_USER"));
        access("svc-token", company, null, "missing").andExpect(jsonPath("$.reason").value("FEATURE_NOT_INCLUDED"));
        access("svc-token", other, null, null).andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("NO_ENTITLEMENT"));
        perform(get("/api/v1/service/access?companyId=" + company + "&featureCode=BAD!"), "svc-token", null)
                .andExpect(status().isBadRequest());

        // usage: idempotent, limited, scoped to the calling product
        usage("consume", "svc-token", company, "note-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1)).andExpect(jsonPath("$.remaining").value(1));
        usage("consume", "svc-token", company, "note-1").andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.replayed").value(true));
        usage("consume", "svc-token", company, "note-2").andExpect(jsonPath("$.used").value(2));
        usage("consume", "svc-token", company, "note-3").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));
        usage("release", "svc-token", company, "del-1").andExpect(jsonPath("$.used").value(1));
        usage("consume", "svc-token", other, "x-1").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENTITLEMENT_REQUIRED"));
        assertThat(jdbc.queryForObject("select count(*) from usage_record where idempotency_key = ?", Integer.class,
                product.code + ":note-1")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ provisioning

    @Test
    void syncProvisioningLaunchAndDelayedDeprovisioning() throws Exception {
        Product product = product("sync", "SYNC");
        UUID company = data.company("pa-" + run, "ACTIVE");
        member(company, "owner", TestData.COMPANY_ADMIN_ROLE);
        String plan = plan(product, 5);
        String sub = subscribe(company, plan);

        JsonNode job = latestJob(company, product.id);
        assertThat(job.get("operation").asString()).isEqualTo("PROVISION");
        assertThat(job.get("status").asString()).isEqualTo("PENDING");
        companyProduct(company, "owner").andExpect(jsonPath("$[0].provisioningState").value("PROVISIONING"))
                .andExpect(jsonPath("$[0].launchUrl").doesNotExist());

        runner.runDue();

        List<ProductServiceStub.Request> calls = stub.requestsFor(company.toString());
        assertThat(calls).hasSize(1);
        ProductServiceStub.Request call = calls.getFirst();
        assertThat(call.method()).isEqualTo("PUT");
        assertThat(call.path()).isEqualTo("/provisioning/v1/tenants/" + company);
        assertThat(call.header("Authorization")).isEqualTo("Bearer " + ProductServiceStub.TOKEN);
        // The token was requested for this product's audience only, so it cannot be replayed at other products.
        assertThat(stub.tokenRequestBodies()).anyMatch(b -> b.contains("scope=" + product.code + "-api"));
        assertThat(call.header("Idempotency-Key")).isEqualTo(job.get("id").asString());
        JsonNode body = json.readTree(call.body());
        assertThat(body.get("companyCode").asString()).isEqualTo("pa-" + run);
        assertThat(body.get("productCode").asString()).isEqualTo(product.code);
        assertThat(body.get("callbackUrl").asString()).endsWith("/api/v1/service/provisioning/jobs/" + job.get("id").asString() + "/callback");

        perform(get("/api/v1/platform/provisioning-jobs/" + job.get("id").asString()), "root", null)
                .andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.lastHttpStatus").value(200)).andExpect(jsonPath("$.configVersionNo").value(1));
        companyProduct(company, "owner").andExpect(jsonPath("$[0].provisioningState").value("PROVISIONED"))
                .andExpect(jsonPath("$[0].launchUrl").value("https://" + product.code + ".occpl.test/c/pa-" + run))
                .andExpect(jsonPath("$[0].openInNewTab").value(true));
        assertThat(auditCount("provisioning.provision_succeeded", company)).isEqualTo(1);

        // the entitlement ends: removal is scheduled after the retention period, not done now
        cancel(company, sub);
        JsonNode deprovision = latestJob(company, product.id);
        assertThat(deprovision.get("operation").asString()).isEqualTo("DEPROVISION");
        assertThat(deprovision.get("status").asString()).isEqualTo("PENDING");
        assertThat(java.time.Instant.parse(deprovision.get("nextAttemptAt").asString()))
                .isAfter(java.time.Instant.now().plus(Duration.ofDays(89)));
        assertThat(state(company, product.id)).isEqualTo("DEPROVISION_SCHEDULED");
        companyProduct(company, "owner").andExpect(jsonPath("$.length()").value(0));
        runner.runDue();
        assertThat(stub.requestsFor(company.toString())).hasSize(1);

        // entitled again before then: the tenant is kept, no new provisioning call
        String sub2 = subscribe(company, plan);
        assertThat(jobStatus(deprovision.get("id").asString())).isEqualTo("CANCELLED");
        assertThat(state(company, product.id)).isEqualTo("PROVISIONED");
        runner.runDue();
        assertThat(stub.requestsFor(company.toString())).hasSize(1);

        // ends again and the retention period passes: the tenant is removed
        cancel(company, sub2);
        String removal = latestJob(company, product.id).get("id").asString();
        jdbc.update("update provisioning_job set next_attempt_at = UTC_TIMESTAMP(6) where id = UUID_TO_BIN(?)", removal);
        stub.script(company.toString(), Response.of(404)); // already gone counts as done
        runner.runDue();
        assertThat(stub.requestsFor(company.toString()).getLast().method()).isEqualTo("DELETE");
        assertThat(jobStatus(removal)).isEqualTo("SUCCEEDED");
        assertThat(state(company, product.id)).isEqualTo("DEPROVISIONED");
    }

    @Test
    void retriesWithBackoffThenFailsPermanentlyWithAlertAndManualRetry() throws Exception {
        Product product = product("retry", "SYNC");
        UUID company = data.company("pr-" + run, "ACTIVE");
        stub.script(company.toString(), Response.of(503), Response.of(500), Response.of(429));
        subscribe(company, plan(product, 5));
        String job = latestJob(company, product.id).get("id").asString();

        runner.runDue();
        JsonNode afterFirst = jobView(job);
        assertThat(afterFirst.get("status").asString()).isEqualTo("PENDING");
        assertThat(afterFirst.get("attempts").asInt()).isEqualTo(1);
        assertThat(afterFirst.get("lastHttpStatus").asInt()).isEqualTo(503);
        assertThat(afterFirst.get("lastError").asString()).contains("HTTP 503");
        assertThat(java.time.Instant.parse(afterFirst.get("nextAttemptAt").asString()))
                .isAfter(java.time.Instant.now().plusSeconds(20));
        runner.runDue(); // not due yet
        assertThat(stub.requestsFor(company.toString())).hasSize(1);

        makeDue(job);
        runner.runDue();
        assertThat(jobView(job).get("attempts").asInt()).isEqualTo(2);
        makeDue(job);
        runner.runDue(); // third and last attempt (maxAttempts = 3)
        JsonNode failed = jobView(job);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("lastHttpStatus").asInt()).isEqualTo(429);
        assertThat(state(company, product.id)).isEqualTo("FAILED");
        assertThat(alertEmails(company)).isEqualTo(1);
        assertThat(auditCount("provisioning.provision_failed", company)).isEqualTo(1);
        perform(get("/api/v1/platform/provisioning-jobs?status=FAILED&companyId=" + company), "root", null)
                .andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(job))
                .andExpect(jsonPath("$.items[0].companyCode").value("pr-" + run));

        // a platform admin retries after the product is fixed
        tokens.register("support", email("support"), true);
        perform(post("/api/v1/platform/provisioning-jobs/" + job + "/retry"), "support", null)
                .andExpect(status().isForbidden());
        perform(post("/api/v1/platform/provisioning-jobs/" + job + "/retry"), "root", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));
        assertThat(state(company, product.id)).isEqualTo("PROVISIONING");
        runner.runDue();
        assertThat(jobStatus(job)).isEqualTo("SUCCEEDED");
        assertThat(state(company, product.id)).isEqualTo("PROVISIONED");
        perform(post("/api/v1/platform/provisioning-jobs/" + job + "/retry"), "root", null)
                .andExpect(status().isUnprocessableContent());
        assertThat(auditCount("provisioning.job_retried", company)).isEqualTo(1);
    }

    @Test
    void clientErrorsFailImmediatelyAndTimeoutsAreRetried() throws Exception {
        Product product = product("perm", "SYNC");
        UUID rejected = data.company("pe-" + run, "ACTIVE");
        stub.script(rejected.toString(), Response.of(400));
        subscribe(rejected, plan(product, 5));
        UUID slow = data.company("pt-" + run, "ACTIVE");
        stub.script(slow.toString(), new Response(200, Duration.ofSeconds(3)));
        subscribe(slow, plan(product, 5));

        runner.runDue();
        JsonNode permanent = latestJob(rejected, product.id);
        assertThat(permanent.get("status").asString()).isEqualTo("FAILED");
        assertThat(permanent.get("attempts").asInt()).isEqualTo(1);
        assertThat(alertEmails(rejected)).isEqualTo(1);

        JsonNode timedOut = latestJob(slow, product.id);
        assertThat(timedOut.get("status").asString()).isEqualTo("PENDING");
        assertThat(timedOut.get("lastError").asString()).startsWith("Timed out");
        assertThat(timedOut.get("lastHttpStatus").isNull()).isTrue();
    }

    @Test
    void asyncProvisioningCompletesThroughTheCallbackOfItsOwnProductOnly() throws Exception {
        Product product = product("async", "ASYNC");
        Product other = product("other", "NONE");
        tokens.registerService("async-token", product.clientId);
        tokens.registerService("other-token", other.clientId);
        UUID company = data.company("pc-" + run, "ACTIVE");
        stub.script(company.toString(), Response.of(202));
        subscribe(company, plan(product, 5));
        runner.runDue();
        String job = latestJob(company, product.id).get("id").asString();
        assertThat(jobView(job).get("status").asString()).isEqualTo("AWAITING_CALLBACK");
        assertThat(state(company, product.id)).isEqualTo("PROVISIONING");

        callback("other-token", job, "SUCCEEDED").andExpect(status().isNotFound());
        callback("async-token", job, "SUCCEEDED").andExpect(status().isNoContent());
        callback("async-token", job, "SUCCEEDED").andExpect(status().isNoContent()); // repeated report
        callback("async-token", job, "FAILED").andExpect(status().isConflict());
        assertThat(jobStatus(job)).isEqualTo("SUCCEEDED");
        assertThat(state(company, product.id)).isEqualTo("PROVISIONED");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'provisioning.callback_received' "
                + "and actor_type = 'SERVICE' and actor_client_id = ? and target_id = ?", Integer.class,
                product.clientId, job)).isEqualTo(1);

        // no callback before the deadline: the attempt counts as failed and is retried
        UUID late = data.company("pl-" + run, "ACTIVE");
        stub.script(late.toString(), Response.of(202));
        subscribe(late, plan(product, 5));
        runner.runDue();
        String lateJob = latestJob(late, product.id).get("id").asString();
        jdbc.update("update provisioning_job set callback_deadline = UTC_TIMESTAMP(6) - interval 1 second "
                + "where id = UUID_TO_BIN(?)", lateJob);
        runner.runDue();
        JsonNode retried = jobView(lateJob);
        assertThat(retried.get("status").asString()).isEqualTo("PENDING");
        assertThat(retried.get("lastError").asString()).contains("callback deadline");

        // platform staff can cancel a waiting job
        perform(post("/api/v1/platform/provisioning-jobs/" + lateJob + "/cancel"), "root", "{\"reason\":\"product offline\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(state(late, product.id)).isEqualTo("FAILED");
    }

    @Test
    void productsWithoutProvisioningAreReadyImmediately() throws Exception {
        Product product = product("none", "NONE");
        UUID company = data.company("pn-" + run, "ACTIVE");
        member(company, "user", TestData.COMPANY_USER_ROLE);
        subscribe(company, plan(product, 5));
        runner.runDue();
        assertThat(stub.requestsFor(company.toString())).isEmpty();
        companyProduct(company, "user").andExpect(jsonPath("$[0].provisioningState").value("PROVISIONED"))
                .andExpect(jsonPath("$[0].launchUrl").value(Matchers.startsWith("https://" + product.code)));
        UUID otherCompany = data.company("po-" + run, "ACTIVE");
        perform(get("/api/v1/companies/" + otherCompany + "/products"), "user", null).andExpect(status().isNotFound());

        jdbc.update("update company set status = 'SUSPENDED' where id = UUID_TO_BIN(?)", company.toString());
        perform(get("/api/v1/companies/" + company + "/products"), "user", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_SUSPENDED"));
    }

    // ------------------------------------------------------------------ helpers

    record Product(String id, String code, String clientId) {
    }

    private Product product(String prefix, String mode) throws Exception {
        String code = prefix + "-" + run;
        String clientId = "svc-" + code;
        String id = body(perform(post("/api/v1/products"), "root", "{\"code\":\"" + code + "\",\"name\":\"" + code
                + "\",\"appUrl\":\"https://" + code + ".occpl.test\",\"apiBaseUrl\":\"" + stub.baseUrl()
                + "\",\"apiAudience\":\"" + code + "-api\",\"serviceClientId\":\"" + clientId + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serviceClientId").value(clientId))).get("id").asString();
        String base = "/api/v1/products/" + id;
        perform(post(base + "/features"), "root", "{\"code\":\"notes\",\"name\":\"Notes\",\"type\":\"LIMIT\",\"unit\":\"RECORDS\",\"resetPeriod\":\"NONE\"}");
        perform(post(base + "/features"), "root", "{\"code\":\"export\",\"name\":\"Export\",\"type\":\"BOOLEAN\"}");
        String provisioning = mode.equals("NONE") ? "{\"mode\":\"NONE\"}"
                : "{\"mode\":\"" + mode + "\",\"path\":\"/provisioning/v1/tenants/{companyId}\",\"timeoutSeconds\":1,\"maxAttempts\":3}";
        perform(post(base + "/versions"), "root", "{\"config\":{\"schemaVersion\":1,\"launch\":{\"path\":\"/c/{companyCode}\","
                + "\"openInNewTab\":true},\"provisioning\":" + provisioning + "}}").andExpect(status().isCreated());
        perform(post(base + "/versions/1/publish"), "root", null).andExpect(status().isOk());
        perform(post(base + "/activate"), "root", null).andExpect(status().isOk());
        return new Product(id, code, clientId);
    }

    private String plan(Product product, long notes) throws Exception {
        String id = body(perform(post("/api/v1/plans"), "root", "{\"code\":\"p-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"plan\":{\"name\":\"Plan\",\"billingPeriod\":\"MONTHLY\",\"trialDays\":0,"
                + "\"prices\":[{\"currency\":\"INR\",\"amountMinor\":100}],\"products\":[{\"productId\":\"" + product.id
                + "\",\"features\":[{\"code\":\"notes\",\"limit\":" + notes + "},{\"code\":\"export\"}]}]}}")
                .andExpect(status().isCreated())).get("id").asString();
        perform(post("/api/v1/plans/" + id + "/publish"), "root", null).andExpect(status().isOk());
        return id;
    }

    private String subscribe(UUID company, String plan) throws Exception {
        return body(perform(post("/api/v1/companies/" + company + "/subscriptions"), "root",
                "{\"planId\":\"" + plan + "\",\"currency\":\"INR\",\"startTrial\":false}")
                .andExpect(status().isCreated())).get("id").asString();
    }

    private void cancel(UUID company, String subscription) throws Exception {
        perform(post("/api/v1/companies/" + company + "/subscriptions/" + subscription + "/cancel"), "root",
                "{\"mode\":\"IMMEDIATE\"}").andExpect(status().isOk());
    }

    private UUID member(UUID company, String name, UUID role) throws Exception {
        tokens.register(name, email(name), false);
        perform(get("/api/v1/me"), name, null).andExpect(status().isOk());
        UUID user = data.userIdByEmail(email(name));
        data.membership(company, user, "ACTIVE", role);
        return user;
    }

    private ResultActions access(String token, UUID company, String subject, String feature) throws Exception {
        String uri = "/api/v1/service/access?companyId=" + company + (subject == null ? "" : "&userSubject=" + subject)
                + (feature == null ? "" : "&featureCode=" + feature);
        return perform(get(uri), token, null).andExpect(status().isOk());
    }

    private ResultActions usage(String op, String token, UUID company, String key) throws Exception {
        return perform(post("/api/v1/service/usage/" + op), token, "{\"companyId\":\"" + company
                + "\",\"featureCode\":\"notes\",\"quantity\":1,\"idempotencyKey\":\"" + key + "\"}");
    }

    private ResultActions callback(String token, String job, String outcome) throws Exception {
        return perform(post("/api/v1/service/provisioning/jobs/" + job + "/callback"), token,
                "{\"outcome\":\"" + outcome + "\",\"message\":\"done\"}");
    }

    private ResultActions companyProduct(UUID company, String as) throws Exception {
        return perform(get("/api/v1/companies/" + company + "/products"), as, null).andExpect(status().isOk());
    }

    private JsonNode latestJob(UUID company, String productId) throws Exception {
        JsonNode page = body(perform(get("/api/v1/platform/provisioning-jobs?companyId=" + company + "&productId="
                + productId), "root", null).andExpect(status().isOk()));
        return page.get("items").get(0);
    }

    private JsonNode jobView(String job) throws Exception {
        return body(perform(get("/api/v1/platform/provisioning-jobs/" + job), "root", null).andExpect(status().isOk()));
    }

    private String jobStatus(String job) {
        return jdbc.queryForObject("select status from provisioning_job where id = UUID_TO_BIN(?)", String.class, job);
    }

    private void makeDue(String job) {
        jdbc.update("update provisioning_job set next_attempt_at = UTC_TIMESTAMP(6) - interval 1 second "
                + "where id = UUID_TO_BIN(?)", job);
    }

    private String state(UUID company, String productId) {
        return jdbc.queryForObject("select state from company_product_provisioning where company_id = UUID_TO_BIN(?) "
                + "and product_id = UUID_TO_BIN(?)", String.class, company.toString(), productId);
    }

    private int alertEmails(UUID company) {
        return jdbc.queryForObject("select count(*) from notification where template = 'provisioning-failed' "
                + "and recipient_email = 'ops@occpl.test' and company_id = UUID_TO_BIN(?)", Integer.class,
                company.toString());
    }

    private int auditCount(String action, UUID company) {
        return jdbc.queryForObject("select count(*) from audit_log where action = ? and company_id = UUID_TO_BIN(?)",
                Integer.class, action, company.toString());
    }

    private String email(String name) {
        return name + "-" + run + "@occpl.test";
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
