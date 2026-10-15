package com.occpl.platform.shared.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.web.CorrelationId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ApiSecurityAndErrorHandlingTest.ProbeController.class)
@Import({SecurityConfiguration.class, ApiSecurityAndErrorHandlingTest.ProbeController.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "occpl.security.cors.allowed-origins=https://admin.occpl.test")
class ApiSecurityAndErrorHandlingTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void rejectsRequestsWithoutTokenUsingProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(header().exists(CorrelationId.HEADER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void allowsAuthenticatedRequestsAndSetsSecurityHeaders() throws Exception {
        mvc.perform(get("/api/v1/probe").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
    }

    @Test
    void returnsFieldErrorsForInvalidBody() throws Exception {
        mvc.perform(post("/api/v1/probe").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("NotBlank"));
    }

    @Test
    void returnsGenericErrorForMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/probe").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("The request could not be processed."));
    }

    @Test
    void mapsApiExceptionToItsStatusAndCode() throws Exception {
        mvc.perform(get("/api/v1/probe/missing").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("https://api.occplsoftech.com/problems/not-found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/probe/missing"));
    }

    @Test
    void hidesInternalDetailsOfUnexpectedErrors() throws Exception {
        mvc.perform(get("/api/v1/probe/boom").with(jwt()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret-db-host"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void answersOverloadWith503AndRetryAfter() throws Exception {
        mvc.perform(get("/api/v1/probe/busy").with(jwt()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
                .andExpect(jsonPath("$.detail").value(not(containsString("hikari"))));
    }

    @Test
    void returnsProblemDetailsForUnsupportedMethod() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/probe").with(jwt()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void allowsCorsPreflightOnlyFromConfiguredOrigins() throws Exception {
        mvc.perform(options("/api/v1/probe")
                        .header("Origin", "https://admin.occpl.test")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://admin.occpl.test"));

        mvc.perform(options("/api/v1/probe")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/probe")
        String probe() {
            return "ok";
        }

        @PostMapping("/api/v1/probe")
        String create(@Valid @RequestBody ProbeRequest request) {
            return request.name();
        }

        @GetMapping("/api/v1/probe/missing")
        String missing() {
            throw ApiException.notFound("Probe");
        }

        @GetMapping("/api/v1/probe/boom")
        String boom() {
            throw new IllegalStateException("connection to secret-db-host failed");
        }

        @GetMapping("/api/v1/probe/busy")
        String busy() {
            throw new org.springframework.transaction.CannotCreateTransactionException("no connection",
                    new java.sql.SQLTransientConnectionException("core-hikari - Connection is not available"));
        }
    }

    record ProbeRequest(@NotBlank @Size(max = 50) String name) {
    }
}
