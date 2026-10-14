package com.occpl.platform.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.occpl.platform.billing.internal.InvoiceService;
import com.occpl.platform.identity.api.IdentityProviderAdmin;
import com.occpl.platform.support.MockTokens;
import com.occpl.platform.support.MySqlTestDatabase;
import com.occpl.platform.support.RazorpayStub;
import com.occpl.platform.support.TestData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Billing against a Razorpay contract stub: invoice issue (manual and scheduled), gateway failures,
 * cancellation, refresh, and signed webhooks (paid → subscription activated/renewed, duplicates,
 * bad signatures, expiry, failed payments) plus who may see and pay invoices.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class BillingIT {

    static final RazorpayStub razorpay;
    static final String WEBHOOK_SECRET = "whsec-test-123";

    static {
        try {
            razorpay = new RazorpayStub();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
        registry.add("occpl.billing.razorpay-api-url", razorpay::baseUrl);
        registry.add("occpl.billing.razorpay-key-id", () -> "rzp_test_key");
        registry.add("occpl.billing.razorpay-key-secret", () -> "rzp_test_secret");
        registry.add("occpl.billing.razorpay-webhook-secret", () -> WEBHOOK_SECRET);
    }

    @AfterAll
    static void stop() {
        razorpay.close();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired InvoiceService invoiceService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean IdentityProviderAdmin idp;

    MockTokens tokens;
    TestData data;
    String run;
    String plan;
    String trialPlan;

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
        String product = product();
        plan = plan(product, 0);
        trialPlan = plan(product, 14);
    }

    @Test
    void issuesInvoicesThroughTheGatewayOncePerPeriod() throws Exception {
        UUID company = companyWithContact("bi");
        String sub = subscribe(company, plan, false);

        perform(get("/api/v1/billing/status"), "root", null).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.webhooksEnabled").value(true));
        JsonNode invoice = body(createInvoice(company, sub).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.amountMinor").value(49900))
                .andExpect(jsonPath("$.currency").value("INR")));
        String gatewayId = invoice.get("gatewayInvoiceId").asString();
        assertThat(invoice.get("paymentUrl").asString()).isEqualTo("https://rzp.io/i/" + gatewayId);

        RazorpayStub.Request request = razorpay.creates().getLast();
        assertThat(request.authorization()).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("rzp_test_key:rzp_test_secret".getBytes(StandardCharsets.UTF_8)));
        JsonNode sent = request.body();
        assertThat(sent.get("type").asString()).isEqualTo("invoice");
        assertThat(sent.get("receipt").asString()).isEqualTo(invoice.get("id").asString());
        assertThat(sent.get("customer").get("email").asString()).isEqualTo("accounts-" + run + "@bi.test");
        assertThat(sent.get("customer").get("name").asString()).isEqualTo("bi-" + run + " Pvt Ltd");
        assertThat(sent.get("line_items").get(0).get("amount").asLong()).isEqualTo(49900);
        assertThat(sent.get("line_items").get(0).get("currency").asString()).isEqualTo("INR");
        assertThat(sent.get("expire_by").asLong()).isGreaterThan(Instant.now().plus(Duration.ofDays(13)).getEpochSecond());
        assertThat(sent.get("notes").get("subscription_id").asString()).isEqualTo(sub);

        createInvoice(company, sub).andExpect(status().isConflict());
        assertThat(audits("billing.invoice_issued", company)).isEqualTo(1);
    }

    @Test
    void companyMembersSeeTheirBillingHistoryAccordingToPermissions() throws Exception {
        UUID company = companyWithContact("bm");
        String sub = subscribe(company, plan, false);
        createInvoice(company, sub).andExpect(status().isCreated());
        member(company, "owner", TestData.COMPANY_ADMIN_ROLE);
        member(company, "staff", TestData.COMPANY_USER_ROLE);
        UUID other = companyWithContact("bo");
        member(other, "stranger", TestData.COMPANY_ADMIN_ROLE);

        perform(get("/api/v1/companies/" + company + "/invoices"), "owner", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.items[0].paymentUrl").exists())
                .andExpect(jsonPath("$.items[0].lastError").doesNotExist());
        perform(get("/api/v1/companies/" + company + "/invoices"), "staff", null).andExpect(status().isForbidden());
        perform(get("/api/v1/companies/" + company + "/invoices"), "stranger", null).andExpect(status().isNotFound());
        perform(post("/api/v1/companies/" + company + "/subscriptions/" + sub + "/invoices"), "owner", null)
                .andExpect(status().isForbidden());
    }

    @Test
    void gatewayRefusalsAreReportedAndCanBeRetried() throws Exception {
        UUID noContact = data.company("bn-" + run, "ACTIVE");
        String orphan = subscribe(noContact, plan, false);
        createInvoice(noContact, orphan).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("contact")));

        UUID company = companyWithContact("bf");
        String sub = subscribe(company, plan, false);
        razorpay.failNextCreate(400);
        createInvoice(company, sub).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYMENT_GATEWAY_ERROR"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("customer email is invalid")));
        perform(get("/api/v1/companies/" + company + "/invoices"), "root", null)
                .andExpect(jsonPath("$.items[0].status").value("ISSUE_FAILED"))
                .andExpect(jsonPath("$.items[0].lastError").value(org.hamcrest.Matchers.containsString("HTTP 400")));
        createInvoice(company, sub).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ISSUED"));
    }

    @Test
    void paidWebhookRenewsTheSubscriptionExactlyOnce() throws Exception {
        UUID company = companyWithContact("bp");
        String sub = subscribe(company, plan, false);
        Instant periodEnd = periodEnd(sub);
        JsonNode invoice = body(createInvoice(company, sub));
        String gatewayId = invoice.get("gatewayInvoiceId").asString();

        // forged or missing signatures change nothing and are audited
        String paid = paidEvent(gatewayId, 49900, "pay_" + run);
        webhook(paid, "evt_" + run + "_1", "deadbeef").andExpect(status().isUnauthorized());
        webhook(paid, "evt_" + run + "_1", null).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'billing.webhook_rejected' "
                + "and occurred_at > UTC_TIMESTAMP(6) - interval 1 minute", Integer.class)).isGreaterThanOrEqualTo(2);
        assertThat(invoiceStatus(invoice)).isEqualTo("ISSUED");

        webhook(paid, null, sign(paid)).andExpect(status().isBadRequest());
        webhook(paid, "evt_" + run + "_1", sign(paid)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("PROCESSED"));
        assertThat(invoiceStatus(invoice)).isEqualTo("PAID");
        assertThat(periodEnd(sub)).isEqualTo(periodEnd.atOffset(java.time.ZoneOffset.UTC).plusMonths(1).toInstant());
        assertThat(subscriptionStatus(sub)).isEqualTo("ACTIVE");
        perform(get("/api/v1/companies/" + company + "/invoices/" + invoice.get("id").asString()), "root", null)
                .andExpect(jsonPath("$.paymentApplied").value("APPLIED"))
                .andExpect(jsonPath("$.amountPaidMinor").value(49900))
                .andExpect(jsonPath("$.payments[0].gatewayPaymentId").value("pay_" + run))
                .andExpect(jsonPath("$.payments[0].status").value("CAPTURED"))
                .andExpect(jsonPath("$.paymentUrl").doesNotExist());

        // replays (same id) and re-deliveries (new id) have no further effect
        webhook(paid, "evt_" + run + "_1", sign(paid)).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        webhook(paid, "evt_" + run + "_2", sign(paid)).andExpect(jsonPath("$.outcome").value("PROCESSED"));
        assertThat(periodEnd(sub)).isEqualTo(periodEnd.atOffset(java.time.ZoneOffset.UTC).plusMonths(1).toInstant());
        assertThat(jdbc.queryForObject("select count(*) from payment_transaction where gateway_payment_id = ?",
                Integer.class, "pay_" + run)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from subscription_history where subscription_id = UUID_TO_BIN(?) "
                + "and event = 'RENEWED'", Integer.class, sub)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select body_sha256 from payment_webhook_event where event_id = ?", String.class,
                "evt_" + run + "_1")).hasSize(64);
    }

    @Test
    void trialIsActivatedByPaymentAndOtherEventsAreRecorded() throws Exception {
        UUID company = companyWithContact("bt");
        String sub = subscribe(company, trialPlan, true);
        JsonNode invoice = body(createInvoice(company, sub));
        String gatewayId = invoice.get("gatewayInvoiceId").asString();

        String failed = paymentEvent("payment.failed", gatewayId, "pay_f_" + run, "failed", "Card declined by bank");
        webhook(failed, "evt_f_" + run, sign(failed)).andExpect(jsonPath("$.outcome").value("PROCESSED"));
        assertThat(audits("billing.payment_failed", company)).isEqualTo(1);
        assertThat(subscriptionStatus(sub)).isEqualTo("TRIAL");

        String unknown = "{\"event\":\"order.paid\",\"payload\":{}}";
        webhook(unknown, "evt_u_" + run, sign(unknown)).andExpect(jsonPath("$.outcome").value("IGNORED"));

        String paid = paidEvent(gatewayId, 49900, "pay_t_" + run);
        webhook(paid, "evt_p_" + run, sign(paid)).andExpect(jsonPath("$.outcome").value("PROCESSED"));
        assertThat(subscriptionStatus(sub)).isEqualTo("ACTIVE");
        // Paying early keeps the rest of the trial: the paid month starts where the invoice says it does.
        Instant invoicedFrom = Instant.parse(invoice.get("periodStart").asString());
        assertThat(periodEnd(sub)).isEqualTo(invoicedFrom.atOffset(java.time.ZoneOffset.UTC).plusMonths(1).toInstant());
        assertThat(jdbc.queryForObject("select trial_end from company_subscription where id = UUID_TO_BIN(?)",
                java.sql.Timestamp.class, sub)).isNull();
    }

    @Test
    void expiredAndCancelledInvoicesFreeThePeriodAndRefreshPicksUpPayments() throws Exception {
        UUID company = companyWithContact("be");
        String sub = subscribe(company, plan, false);
        JsonNode first = body(createInvoice(company, sub));
        String expired = invoiceEvent("invoice.expired", first.get("gatewayInvoiceId").asString(), "expired", 0);
        webhook(expired, "evt_e_" + run, sign(expired)).andExpect(status().isOk());
        assertThat(invoiceStatus(first)).isEqualTo("EXPIRED");

        JsonNode second = body(createInvoice(company, sub).andExpect(status().isCreated()));
        perform(post("/api/v1/companies/" + company + "/invoices/" + second.get("id").asString() + "/cancel"), "root", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(razorpay.requests).anyMatch(r -> r.path().equals("/v1/invoices/" + second.get("gatewayInvoiceId").asString() + "/cancel"));

        JsonNode third = body(createInvoice(company, sub).andExpect(status().isCreated()));
        Instant periodEnd = periodEnd(sub);
        razorpay.setState(third.get("gatewayInvoiceId").asString(), "paid"); // paid, but the webhook never arrived
        perform(post("/api/v1/companies/" + company + "/invoices/" + third.get("id").asString() + "/refresh"), "root", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentApplied").value("APPLIED"));
        assertThat(periodEnd(sub)).isAfter(periodEnd);
        perform(post("/api/v1/companies/" + company + "/invoices/" + third.get("id").asString() + "/cancel"), "root", null)
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void underpaidInvoicesAreNotPaidAndOversizedWebhooksAreRefused() throws Exception {
        UUID company = companyWithContact("bu");
        String sub = subscribe(company, plan, false);
        JsonNode invoice = body(createInvoice(company, sub));
        Instant periodEnd = periodEnd(sub);
        String underpaid = paidEvent(invoice.get("gatewayInvoiceId").asString(), 100, "pay_u_" + run);
        webhook(underpaid, "evt_under_" + run, sign(underpaid)).andExpect(jsonPath("$.outcome").value("AMOUNT_MISMATCH"));
        assertThat(invoiceStatus(invoice)).isEqualTo("ISSUED");
        assertThat(periodEnd(sub)).isEqualTo(periodEnd);
        assertThat(audits("billing.payment_mismatch", company)).isEqualTo(1);

        String huge = "{\"event\":\"invoice.paid\",\"pad\":\"" + "x".repeat(70 * 1024) + "\"}";
        webhook(huge, "evt_huge_" + run, sign(huge)).andExpect(status().isBadRequest());
    }

    @Test
    void renewalInvoicesAreCreatedAheadOfThePeriodEndOnce() throws Exception {
        UUID company = companyWithContact("bs");
        String due = subscribe(company, plan, false);
        jdbc.update("update company_subscription set current_period_end = UTC_TIMESTAMP(6) + interval 3 day "
                + "where id = UUID_TO_BIN(?)", due);
        UUID other = companyWithContact("bc");
        String cancelling = subscribe(other, plan, false);
        jdbc.update("update company_subscription set current_period_end = UTC_TIMESTAMP(6) + interval 3 day, "
                + "cancel_at_period_end = true where id = UUID_TO_BIN(?)", cancelling);

        invoiceService.createDueInvoices();
        invoiceService.createDueInvoices();
        assertThat(invoiceCount(due)).isEqualTo(1);
        assertThat(invoiceCount(cancelling)).isZero();
        assertThat(jdbc.queryForObject("select actor_type from audit_log where action = 'billing.invoice_issued' "
                + "and company_id = UUID_TO_BIN(?)", String.class, company.toString())).isEqualTo("SYSTEM");
        perform(get("/api/v1/platform/invoices?companyId=" + company), "root", null)
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    // ------------------------------------------------------------------ helpers

    private String product() throws Exception {
        String code = "bill-" + run;
        String id = body(perform(post("/api/v1/products"), "root", "{\"code\":\"" + code + "\",\"name\":\"Billing "
                + run + "\",\"appUrl\":\"https://" + code + ".occpl.test\"}")).get("id").asString();
        perform(post("/api/v1/products/" + id + "/features"), "root",
                "{\"code\":\"export\",\"name\":\"Export\",\"type\":\"BOOLEAN\"}");
        perform(post("/api/v1/products/" + id + "/versions"), "root", "{\"config\":{\"schemaVersion\":1,\"provisioning\":{\"mode\":\"NONE\"}}}");
        perform(post("/api/v1/products/" + id + "/versions/1/publish"), "root", null);
        perform(post("/api/v1/products/" + id + "/activate"), "root", null).andExpect(status().isOk());
        return id;
    }

    private String plan(String product, int trialDays) throws Exception {
        String id = body(perform(post("/api/v1/plans"), "root", "{\"code\":\"p-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"plan\":{\"name\":\"Pro\",\"billingPeriod\":\"MONTHLY\",\"trialDays\":" + trialDays + ","
                + "\"prices\":[{\"currency\":\"INR\",\"amountMinor\":49900}],\"products\":[{\"productId\":\"" + product
                + "\",\"features\":[{\"code\":\"export\"}]}]}}").andExpect(status().isCreated())).get("id").asString();
        perform(post("/api/v1/plans/" + id + "/publish"), "root", null).andExpect(status().isOk());
        return id;
    }

    private UUID companyWithContact(String prefix) throws Exception {
        UUID company = data.company(prefix + "-" + run, "ACTIVE");
        perform(put("/api/v1/companies/" + company + "/contacts/BILLING"), "root",
                "{\"name\":\"Accounts\",\"email\":\"accounts-" + run + "@" + prefix + ".test\"}").andExpect(status().isOk());
        return company;
    }

    private String subscribe(UUID company, String planId, boolean trial) throws Exception {
        return body(perform(post("/api/v1/companies/" + company + "/subscriptions"), "root",
                "{\"planId\":\"" + planId + "\",\"currency\":\"INR\",\"startTrial\":" + trial + "}")
                .andExpect(status().isCreated())).get("id").asString();
    }

    private void member(UUID company, String name, UUID role) throws Exception {
        tokens.register(name, email(name), false);
        perform(get("/api/v1/me"), name, null).andExpect(status().isOk());
        data.membership(company, data.userIdByEmail(email(name)), "ACTIVE", role);
    }

    private ResultActions createInvoice(UUID company, String sub) throws Exception {
        return perform(post("/api/v1/companies/" + company + "/subscriptions/" + sub + "/invoices"), "root", null);
    }

    private String paidEvent(String gatewayInvoiceId, long amount, String paymentId) throws Exception {
        return json.writeValueAsString(Map.of("entity", "event", "event", "invoice.paid",
                "contains", List.of("payment", "order", "invoice"), "created_at", Instant.now().getEpochSecond(),
                "payload", Map.of(
                        "payment", Map.of("entity", Map.of("id", paymentId, "amount", amount, "currency", "INR",
                                "status", "captured", "method", "upi", "invoice_id", gatewayInvoiceId)),
                        "invoice", Map.of("entity", Map.of("id", gatewayInvoiceId, "status", "paid",
                                "amount_paid", amount, "paid_at", Instant.now().getEpochSecond())))));
    }

    private String invoiceEvent(String event, String gatewayInvoiceId, String state, long amountPaid) throws Exception {
        return json.writeValueAsString(Map.of("entity", "event", "event", event, "payload", Map.of("invoice",
                Map.of("entity", Map.of("id", gatewayInvoiceId, "status", state, "amount_paid", amountPaid)))));
    }

    private String paymentEvent(String event, String gatewayInvoiceId, String paymentId, String state, String error)
            throws Exception {
        return json.writeValueAsString(Map.of("entity", "event", "event", event, "payload", Map.of("payment",
                Map.of("entity", Map.of("id", paymentId, "amount", 49900, "currency", "INR", "status", state,
                        "invoice_id", gatewayInvoiceId, "error_description", error)))));
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions webhook(String body, String eventId, String signature) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/webhooks/payments/razorpay")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (eventId != null) {
            request.header("x-razorpay-event-id", eventId);
        }
        if (signature != null) {
            request.header("X-Razorpay-Signature", signature);
        }
        return mvc.perform(request);
    }

    private String invoiceStatus(JsonNode invoice) {
        return jdbc.queryForObject("select status from invoice where id = UUID_TO_BIN(?)", String.class,
                invoice.get("id").asString());
    }

    private int invoiceCount(String subscription) {
        return jdbc.queryForObject("select count(*) from invoice where subscription_id = UUID_TO_BIN(?)", Integer.class,
                subscription);
    }

    private Instant periodEnd(String sub) {
        return jdbc.queryForObject("select current_period_end from company_subscription where id = UUID_TO_BIN(?)",
                java.sql.Timestamp.class, sub).toInstant();
    }

    private String subscriptionStatus(String sub) {
        return jdbc.queryForObject("select status from company_subscription where id = UUID_TO_BIN(?)", String.class, sub);
    }

    private int audits(String action, UUID company) {
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
