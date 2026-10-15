package com.occpl.sample;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls Core's service API ({@code /api/v1/service/**}) with this product's client-credentials
 * token. Fails closed: when Core cannot be reached, requests are refused with 503.
 */
@Component
public class CoreClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final SampleProperties.Core core;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    CoreClient(SampleProperties properties, JsonMapper json) {
        this.core = properties.core();
        this.json = json;
    }

    public record Access(boolean allowed, String reason, Long limit, Long used) {
    }

    public record Usage(long used, Long limit, boolean replayed) {
    }

    /** Thrown when Core refuses a usage change (e.g. 409 LIMIT_EXCEEDED, 403 ENTITLEMENT_REQUIRED). */
    public static class UsageRejected extends RuntimeException {
        private final int status;
        private final String code;

        UsageRejected(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    public Access access(UUID companyId, String userSubject, String featureCode) {
        String query = "companyId=" + companyId + "&userSubject=" + encode(userSubject)
                + (featureCode == null ? "" : "&featureCode=" + encode(featureCode));
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/service/access?" + query)).GET());
        if (response.statusCode() != 200) {
            throw unavailable("access check returned HTTP " + response.statusCode());
        }
        JsonNode body = json.readTree(response.body());
        return new Access(body.path("allowed").asBoolean(false), body.path("reason").asString("UNKNOWN"),
                body.path("limit").isNumber() ? body.path("limit").asLong() : null,
                body.path("used").isNumber() ? body.path("used").asLong() : null);
    }

    public Usage consume(UUID companyId, String featureCode, long quantity, String idempotencyKey) {
        return usage("consume", companyId, featureCode, quantity, idempotencyKey);
    }

    public Usage release(UUID companyId, String featureCode, long quantity, String idempotencyKey) {
        return usage("release", companyId, featureCode, quantity, idempotencyKey);
    }

    private Usage usage(String operation, UUID companyId, String featureCode, long quantity, String key) {
        String body = json.writeValueAsString(Map.of("companyId", companyId.toString(), "featureCode", featureCode,
                "quantity", quantity, "idempotencyKey", key));
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/service/usage/" + operation))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
        if (response.statusCode() == 200) {
            JsonNode result = json.readTree(response.body());
            return new Usage(result.path("used").asLong(), result.path("limit").isNumber()
                    ? result.path("limit").asLong() : null, result.path("replayed").asBoolean(false));
        }
        if (response.statusCode() == 409 || response.statusCode() == 403) {
            String code = response.body().isBlank() ? "UNKNOWN" : json.readTree(response.body()).path("code").asString("UNKNOWN");
            throw new UsageRejected(response.statusCode(), code);
        }
        throw unavailable("usage " + operation + " returned HTTP " + response.statusCode());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + token()).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                invalidate();
            }
            return response;
        } catch (IOException e) {
            throw unavailable("Core unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("interrupted");
        }
    }

    private synchronized String token() throws IOException, InterruptedException {
        if (token != null && Instant.now().isBefore(tokenExpiry.minusSeconds(30))) {
            return token;
        }
        String form = "grant_type=client_credentials&client_id=" + encode(core.clientId())
                + "&client_secret=" + encode(core.clientSecret());
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(core.tokenUri())).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw unavailable("token endpoint returned HTTP " + response.statusCode());
        }
        JsonNode body = json.readTree(response.body());
        token = body.path("access_token").asString();
        tokenExpiry = Instant.now().plusSeconds(body.path("expires_in").asLong(60));
        return token;
    }

    private synchronized void invalidate() {
        token = null;
    }

    private URI uri(String path) {
        return URI.create(core.baseUrl().replaceAll("/+$", "") + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The platform is unavailable (" + reason + ").");
    }
}
