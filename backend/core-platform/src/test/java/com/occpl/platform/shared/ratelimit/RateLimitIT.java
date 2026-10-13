package com.occpl.platform.shared.ratelimit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.occpl.platform.support.MySqlTestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

/** Distributed rate limiting against a real Redis. */
@ActiveProfiles("test")
@SpringBootTest(properties = {"occpl.rate-limit.enabled=true", "occpl.rate-limit.anonymous-per-minute=3",
        "occpl.rate-limit.authenticated-per-minute=4"})
@AutoConfigureMockMvc
class RateLimitIT {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
        registry.add("occpl.rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void limitsAnonymousRequestsPerClientIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/me").with(r -> { r.setRemoteAddr("203.0.113.7"); return r; }))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/me").with(r -> { r.setRemoteAddr("203.0.113.7"); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(get("/api/v1/me").with(r -> { r.setRemoteAddr("203.0.113.8"); return r; }))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void limitsAuthenticatedRequestsPerUser() throws Exception {
        willThrow(new BadJwtException("unknown")).given(jwtDecoder).decode(anyString());
        String run = UUID.randomUUID().toString().substring(0, 8);
        token("user-a", "sub-a-" + run, "a-" + run + "@x.test");
        token("user-b", "sub-b-" + run, "b-" + run + "@x.test");

        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer user-a"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(3 - i)));
        }
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer user-a"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer user-b"))
                .andExpect(status().isOk());
    }

    private void token(String token, String subject, String email) {
        willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(subject).claim("email", email)
                .claim("email_verified", true).claim("amr", List.of("pwd"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build())
                .given(jwtDecoder).decode(token);
    }
}
