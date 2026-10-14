package com.occpl.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.mysql.MySQLContainer;

/** The sample product against a real MySQL and a fake Core: provisioning rules and the notes API. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class SampleProductIT {

    static final MySQLContainer MYSQL = new MySQLContainer(System.getProperty("occpl.test.mysql-image", "mysql:8.4"));
    static final FakeCore CORE;

    static {
        MYSQL.start();
        try {
            CORE = new FakeCore();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("sample.core.base-url", CORE::baseUrl);
        registry.add("sample.core.token-uri", () -> CORE.baseUrl() + "/token");
        registry.add("sample.core.client-secret", () -> "sample-secret");
    }

    @AfterAll
    static void stop() {
        CORE.close();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;

    UUID company;

    @BeforeEach
    void setUp() {
        company = UUID.randomUUID();
        willThrow(new BadJwtException("unknown")).given(decoder).decode(anyString());
        token("core", "occpl-core-services", null);
        token("other-service", "some-other-client", null);
        token("alice", null, "alice-sub");
        token("mallory", null, "mallory-sub");
    }

    @Test
    void onlyCoresServiceClientCanProvisionAndCallsAreIdempotent() throws Exception {
        provision("alice").andExpect(status().isForbidden());
        provision("other-service").andExpect(status().isForbidden());
        mvc.perform(put("/provisioning/v1/tenants/" + company)).andExpect(status().isUnauthorized());
        perform(put("/provisioning/v1/tenants/" + company), "core", "{\"companyId\":\"" + UUID.randomUUID()
                + "\",\"companyCode\":\"x\"}").andExpect(status().isBadRequest());

        provision("core").andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true));
        provision("core").andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));
        assertThat(jdbc.queryForObject("select count(*) from tenant where company_id = ?", Integer.class,
                company.toString())).isEqualTo(1);

        // Core's token cannot use the user API
        perform(get("/api/companies/" + company + "/notes"), "core", null).andExpect(status().isForbidden());
        perform(delete("/provisioning/v1/tenants/" + company), "alice", null).andExpect(status().isForbidden());
        perform(delete("/provisioning/v1/tenants/" + company), "core", null).andExpect(status().isNoContent());
        perform(delete("/provisioning/v1/tenants/" + company), "core", null).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select status from tenant where company_id = ?", String.class,
                company.toString())).isEqualTo("REMOVED");
    }

    @Test
    void notesAreAuthorisedByCoreAndCountedAgainstTheLimit() throws Exception {
        provision("core").andExpect(status().isOk());
        AtomicInteger used = new AtomicInteger();
        CORE.answer("/api/v1/service/access", call -> {
            if (!call.query().contains("companyId=" + company)) {
                return new FakeCore.Answer(200, "{\"allowed\":false,\"reason\":\"NO_ENTITLEMENT\"}");
            }
            if (call.query().contains("userSubject=mallory-sub")) {
                return new FakeCore.Answer(200, "{\"allowed\":false,\"reason\":\"NOT_A_MEMBER\"}");
            }
            return new FakeCore.Answer(200, "{\"allowed\":true,\"reason\":\"OK\",\"limit\":2,\"used\":" + used.get() + "}");
        });
        CORE.answer("/api/v1/service/usage/consume", call -> used.get() >= 2
                ? new FakeCore.Answer(409, "{\"code\":\"LIMIT_EXCEEDED\"}")
                : new FakeCore.Answer(200, "{\"used\":" + used.incrementAndGet() + ",\"limit\":2,\"replayed\":false}"));
        CORE.answer("/api/v1/service/usage/release", call ->
                new FakeCore.Answer(200, "{\"used\":" + used.decrementAndGet() + ",\"limit\":2,\"replayed\":false}"));

        String first = note("alice", "First").andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value("alice-sub")).andReturn().getResponse().getHeader("Location");
        note("alice", "Second").andExpect(status().isCreated());
        note("alice", "Third").andExpect(status().isConflict());
        perform(get("/api/companies/" + company + "/notes"), "alice", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // the access check and usage carry this product's service token and the user's subject
        FakeCore.Call check = CORE.calls("/api/v1/service/access").getLast();
        assertThat(check.authorization()).isEqualTo("Bearer sample-service-token");
        assertThat(check.query()).contains("userSubject=alice-sub");
        FakeCore.Call consume = CORE.calls("/api/v1/service/usage/consume").getFirst();
        assertThat(consume.body()).contains("\"featureCode\":\"notes\"").contains("\"idempotencyKey\":\"note-");
        assertThat(CORE.tokenRequests).isEqualTo(1);

        perform(delete(first), "alice", null).andExpect(status().isNoContent());
        assertThat(CORE.calls("/api/v1/service/usage/release")).hasSize(1);
        note("alice", "Third again").andExpect(status().isCreated());

        // a non-member and a company that is not provisioned here get 404
        perform(get("/api/companies/" + company + "/notes"), "mallory", null).andExpect(status().isNotFound());
        perform(get("/api/companies/" + UUID.randomUUID() + "/notes"), "alice", null).andExpect(status().isNotFound());
        perform(post("/api/companies/" + company + "/notes"), "alice", "{\"title\":\"\"}").andExpect(status().isBadRequest());

        // Core says the subscription ended → refused; Core down → 503 (fail closed)
        CORE.answer("/api/v1/service/access", call -> new FakeCore.Answer(200, "{\"allowed\":false,\"reason\":\"NO_ENTITLEMENT\"}"));
        note("alice", "Late").andExpect(status().isForbidden());
        CORE.answer("/api/v1/service/access", call -> new FakeCore.Answer(500, "{}"));
        perform(get("/api/companies/" + company + "/notes"), "alice", null).andExpect(status().isServiceUnavailable());

        // deprovisioning removes the company's notes
        perform(delete("/provisioning/v1/tenants/" + company), "core", null).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from note where company_id = ?", Integer.class,
                company.toString())).isZero();
    }

    private void token(String value, String clientId, String subject) {
        Jwt.Builder jwt = Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(subject == null ? "service-account-" + clientId : subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        if (clientId != null) {
            jwt.claim("client_id", clientId);
        }
        willReturn(jwt.build()).given(decoder).decode(value);
    }

    private ResultActions provision(String token) throws Exception {
        return perform(put("/provisioning/v1/tenants/" + company), token, "{\"jobId\":\"" + UUID.randomUUID()
                + "\",\"companyId\":\"" + company + "\",\"companyCode\":\"acme\",\"companyName\":\"Acme\"}");
    }

    private ResultActions note(String token, String title) throws Exception {
        return perform(post("/api/companies/" + company + "/notes"), token, "{\"title\":\"" + title + "\"}");
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }
}
