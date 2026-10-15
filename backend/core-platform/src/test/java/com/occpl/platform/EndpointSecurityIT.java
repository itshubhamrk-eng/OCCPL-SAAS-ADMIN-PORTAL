package com.occpl.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.occpl.platform.identity.api.IdentityProviderAdmin;
import com.occpl.platform.support.MockTokens;
import com.occpl.platform.support.MySqlTestDatabase;
import com.occpl.platform.support.TestData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hardening suite (BACKLOG H-2): derived from the application's own request mappings, so every new
 * endpoint is covered automatically and a missing authorization rule fails the build.
 *
 * <ul>
 *   <li>every endpoint declares its authorization (or is in {@link #NO_PRE_AUTHORIZE} with a reason);</li>
 *   <li>anonymous requests are rejected everywhere except the signed webhook;</li>
 *   <li>a company admin cannot reach any endpoint of another company (always 404, also with a body);</li>
 *   <li>ids of another company's resources are not found through one's own company path, neither for
 *       company admins nor for platform staff.</li>
 * </ul>
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class EndpointSecurityIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
        // Billing enabled so its endpoints reach their lookups; the gateway is never called (unreachable port).
        registry.add("occpl.billing.razorpay-api-url", () -> "http://127.0.0.1:9");
        registry.add("occpl.billing.razorpay-key-id", () -> "rzp_test_key");
        registry.add("occpl.billing.razorpay-key-secret", () -> "rzp_test_secret");
    }

    /** Endpoints without {@code @PreAuthorize} and why that is safe. */
    private static final Map<String, String> NO_PRE_AUTHORIZE = Map.of(
            "GET /api/v1/me", "own data of any signed-in user",
            "GET /api/v1/me/profile", "own data of any signed-in user",
            "PUT /api/v1/me/profile", "own data of any signed-in user",
            "POST /api/v1/invitations/accept", "possession of the one-time invitation token",
            "GET /api/v1/permissions", "public permission catalogue for signed-in users",
            "GET /api/v1/service/access", "security chain: SERVICE authority; product from the client id",
            "POST /api/v1/service/usage/consume", "security chain: SERVICE authority; product from the client id",
            "POST /api/v1/service/usage/release", "security chain: SERVICE authority; product from the client id",
            "POST /api/v1/service/provisioning/jobs/{jobId}/callback", "security chain: SERVICE authority; job must belong to the caller's product",
            "POST /api/v1/webhooks/payments/razorpay", "HMAC signature of the raw body");

    private static final Set<String> ANONYMOUS_ALLOWED = Set.of("POST /api/v1/webhooks/payments/razorpay");

    /** Smallest valid bodies, so that requests reach authorization and lookups rather than validation. */
    private static final Map<String, String> BODIES = Map.ofEntries(
            Map.entry("PUT /api/v1/companies/{companyId}/memberships/{membershipId}/roles",
                    "{\"roles\":[\"COMPANY_USER\"]}"),
            Map.entry("POST /api/v1/companies/{companyId}/subscriptions/{subscriptionId}/change-plan",
                    "{\"planId\":\"" + UUID.randomUUID() + "\"}"),
            Map.entry("POST /api/v1/companies/{companyId}/subscriptions/{subscriptionId}/cancel",
                    "{\"mode\":\"IMMEDIATE\"}"),
            Map.entry("POST /api/v1/companies/{companyId}/subscriptions/{subscriptionId}/suspend", "{}"),
            Map.entry("POST /api/v1/companies/{companyId}/memberships/{membershipId}/suspend", "{}"),
            Map.entry("POST /api/v1/companies/{companyId}/memberships/{membershipId}/deactivate", "{}"));

    private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)}");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean IdentityProviderAdmin idp;

    MockTokens tokens;
    TestData data;
    String run;
    UUID companyA;
    UUID companyB;

    record Endpoint(String method, String path, HandlerMethod handler) {
        String key() {
            return method + " " + path;
        }

        Optional<String> preAuthorize() {
            PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            PreAuthorize onType = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            return Optional.ofNullable(onMethod != null ? onMethod : onType).map(PreAuthorize::value);
        }

        boolean companyScoped() {
            return path.startsWith("/api/v1/companies/{companyId}");
        }

        Set<String> variables() {
            Set<String> names = new TreeSet<>();
            Matcher m = VARIABLE.matcher(path);
            while (m.find()) {
                names.add(m.group(1));
            }
            return names;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        tokens = new MockTokens(jwtDecoder);
        data = new TestData(jdbc);
        run = UUID.randomUUID().toString().substring(0, 6);
        given(idp.findByEmail(anyString())).willReturn(Optional.empty());
        given(idp.createUser(anyString(), any(), any(), any())).willAnswer(i -> "kc-" + UUID.randomUUID());
        companyA = data.company("sa-" + run, "ACTIVE");
        companyB = data.company("sb-" + run, "ACTIVE");
        tokens.register("root", email("root"), true);
        perform(get("/api/v1/me"), "root", null).andExpect(status().isOk());
        data.platformRole(data.userIdByEmail(email("root")), TestData.SUPER_ADMIN_ROLE);
        tokens.register("admin-a", email("admin-a"), false);
        perform(get("/api/v1/me"), "admin-a", null).andExpect(status().isOk());
        data.membership(companyA, data.userIdByEmail(email("admin-a")), "ACTIVE", TestData.COMPANY_ADMIN_ROLE);
    }

    List<Endpoint> endpoints() {
        List<Endpoint> all = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            if (!e.getValue().getBeanType().getPackageName().startsWith("com.occpl.platform")) {
                continue; // springdoc, error controller
            }
            for (String path : e.getKey().getPatternValues()) {
                for (RequestMethod method : e.getKey().getMethodsCondition().getMethods()) {
                    all.add(new Endpoint(method.name(), path, e.getValue()));
                }
            }
        }
        assertThat(all).hasSizeGreaterThan(80);
        return all;
    }

    @Test
    void everyEndpointDeclaresItsAuthorization() {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Endpoint e : endpoints()) {
            seen.add(e.key());
            Optional<String> rule = e.preAuthorize();
            if (rule.isEmpty() && !NO_PRE_AUTHORIZE.containsKey(e.key())) {
                problems.add(e.key() + ": no @PreAuthorize");
            }
            if (e.companyScoped()) {
                String r = rule.orElse("");
                boolean tenantChecked = r.startsWith("@tenant.check(#companyId,");
                boolean staffOnly = r.matches("hasAuthority\\('platform\\.[a-z.]+'\\)");
                if (!tenantChecked && !staffOnly) {
                    problems.add(e.key() + ": company endpoint must use @tenant.check(#companyId, ...) or a platform authority, has: " + r);
                }
            }
        }
        NO_PRE_AUTHORIZE.keySet().stream().filter(k -> !seen.contains(k))
                .forEach(k -> problems.add(k + ": allow-list entry without an endpoint"));
        assertThat(problems).isEmpty();
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (ANONYMOUS_ALLOWED.contains(e.key())) {
                continue;
            }
            int status = mvc.perform(build(e, Map.of(), "{}")).andReturn().getResponse().getStatus();
            if (status != 401) {
                problems.add(e.key() + " → " + status);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void usersCannotCallTheServiceApiAndServicesCannotCallUserEndpoints() throws Exception {
        tokens.registerService("svc", "some-product-client");
        List<String> problems = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (ANONYMOUS_ALLOWED.contains(e.key())) {
                continue;
            }
            boolean serviceApi = e.path().startsWith("/api/v1/service/");
            String token = serviceApi ? "admin-a" : "svc";
            int status = perform(build(e, Map.of("companyId", companyA.toString()), "{}"), token, null)
                    .andReturn().getResponse().getStatus();
            if (status != 403) {
                problems.add(e.key() + " with a " + (serviceApi ? "user" : "service") + " token → " + status);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void companyMembersCannotReachAnotherCompany() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (!e.companyScoped()) {
                continue;
            }
            for (String body : new String[] {null, "{}", BODIES.getOrDefault(e.key(), "{}")}) {
                int status = perform(build(e, Map.of("companyId", companyB.toString()), body), "admin-a", null)
                        .andReturn().getResponse().getStatus();
                if (status != 404) {
                    problems.add(e.key() + " (body " + body + ") → " + status);
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void resourcesOfAnotherCompanyAreNotFoundThroughOwnCompanyPaths() throws Exception {
        Map<String, String> foreign = foreignResources();
        List<String> problems = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (!e.companyScoped() || e.variables().size() < 2) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            values.put("companyId", companyA.toString());
            boolean idVariable = false;
            for (String name : e.variables()) {
                if (name.equals("companyId")) {
                    continue;
                }
                if (name.equals("type")) {
                    values.put(name, "BILLING"); // contact type, not an id
                    continue;
                }
                assertThat(foreign).as("a foreign %s for %s", name, e.key()).containsKey(name);
                values.put(name, foreign.get(name));
                idVariable = true;
            }
            if (!idVariable) {
                continue;
            }
            String body = BODIES.getOrDefault(e.key(), e.method().equals("GET") ? null : "{}");
            for (String token : List.of("admin-a", "root")) {
                // Members of the own company lack platform authorities: staff-only endpoints answer 403 before any lookup.
                boolean staffOnly = e.preAuthorize().orElse("").startsWith("hasAuthority");
                int expected = staffOnly && token.equals("admin-a") ? 403 : 404;
                int status = perform(build(e, values, body), token, null).andReturn().getResponse().getStatus();
                if (status != expected) {
                    problems.add(e.key() + " as " + token + " → " + status + " (expected " + expected + ")");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    /** One resource of each kind in company B (ids keyed by the path variable name). */
    private Map<String, String> foreignResources() throws Exception {
        Map<String, String> ids = new LinkedHashMap<>();
        tokens.register("member-b", email("member-b"), false);
        perform(get("/api/v1/me"), "member-b", null).andExpect(status().isOk());
        ids.put("membershipId", data.membership(companyB, data.userIdByEmail(email("member-b")), "ACTIVE",
                TestData.COMPANY_USER_ROLE).toString());
        ids.put("invitationId", json.readTree(perform(post("/api/v1/companies/" + companyB + "/invitations"), "root",
                "{\"email\":\"invitee-" + run + "@b.test\",\"roles\":[\"COMPANY_USER\"]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString());
        String product = json.readTree(perform(post("/api/v1/products"), "root", "{\"code\":\"sec-" + run
                + "\",\"name\":\"Sec " + run + "\",\"appUrl\":\"https://sec-" + run + ".occpl.test\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        perform(post("/api/v1/products/" + product + "/versions"), "root",
                "{\"config\":{\"schemaVersion\":1,\"provisioning\":{\"mode\":\"NONE\"}}}");
        perform(post("/api/v1/products/" + product + "/versions/1/publish"), "root", null);
        perform(post("/api/v1/products/" + product + "/activate"), "root", null).andExpect(status().isOk());
        String plan = json.readTree(perform(post("/api/v1/plans"), "root", "{\"code\":\"sec-" + run
                + "\",\"plan\":{\"name\":\"Sec\",\"billingPeriod\":\"MONTHLY\",\"trialDays\":0,"
                + "\"prices\":[{\"currency\":\"INR\",\"amountMinor\":100}],\"products\":[{\"productId\":\""
                + product + "\",\"features\":[]}]}}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        perform(post("/api/v1/plans/" + plan + "/publish"), "root", null).andExpect(status().isOk());
        String subscription = json.readTree(perform(post("/api/v1/companies/" + companyB + "/subscriptions"), "root",
                "{\"planId\":\"" + plan + "\",\"currency\":\"INR\",\"startTrial\":false}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        ids.put("subscriptionId", subscription);
        UUID invoice = UUID.randomUUID();
        jdbc.update("""
                insert into invoice (id, company_id, subscription_id, gateway, gateway_invoice_id, status, description,
                  currency, amount_minor, period_start, period_end, customer_email, created_at, updated_at, version)
                values (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), 'RAZORPAY', ?, 'ISSUED', 'Test', 'INR', 100,
                  UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + interval 1 month, 'b@b.test', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0)""",
                invoice.toString(), companyB.toString(), subscription, "inv_" + run);
        ids.put("invoiceId", invoice.toString());
        return ids;
    }

    private MockHttpServletRequestBuilder build(Endpoint e, Map<String, String> values, String body) {
        Matcher m = VARIABLE.matcher(e.path());
        StringBuilder uri = new StringBuilder();
        while (m.find()) {
            String value = values.getOrDefault(m.group(1), m.group(1).equals("versionNo") ? "1"
                    : m.group(1).equals("type") ? "BILLING" : UUID.randomUUID().toString());
            m.appendReplacement(uri, value);
        }
        m.appendTail(uri);
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(e.method()), uri.toString());
        if (body != null && !e.method().equals("GET")) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request;
    }

    private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request,
            String token, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private String email(String name) {
        return name + "-" + run + "@sec.test";
    }
}
