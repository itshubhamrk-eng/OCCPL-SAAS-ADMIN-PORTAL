package com.occpl.platform.provisioning.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls a product service's provisioning endpoint (ARCHITECTURE.md §7): {@code PUT} to provision,
 * {@code DELETE} to deprovision, both idempotent on the product side and carrying the job id as
 * {@code Idempotency-Key}. Runs outside any database transaction.
 */
@Component
class ProductServiceClient {

    private static final int MAX_RESPONSE_SNIPPET = 300;

    private final ServiceTokenProvider tokens;
    private final JsonMapper jsonMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    ProductServiceClient(ServiceTokenProvider tokens, JsonMapper jsonMapper) {
        this.tokens = tokens;
        this.jsonMapper = jsonMapper;
    }

    /** @param audience the product's API audience; the service token is limited to it */
    record Call(UUID jobId, ProvisioningJob.Operation operation, URI uri, Map<String, Object> body, Duration timeout,
            String audience) {
    }

    /** {@code status} is null when no HTTP response was received; {@code error} explains failures. */
    record Result(Integer status, String error) {

        boolean is2xx() {
            return status != null && status >= 200 && status < 300;
        }

        /** Network errors, timeouts, 5xx, 408, 429 and 401 (token renewed next time) may succeed later; other 4xx will not. */
        boolean retryable() {
            return status == null || status >= 500 || status == 408 || status == 429 || status == 401;
        }
    }

    Result execute(Call call) {
        String token;
        try {
            token = tokens.token(call.audience());
        } catch (ServiceTokenProvider.TokenUnavailableException e) {
            return new Result(null, e.getMessage());
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(call.uri()).timeout(call.timeout())
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", call.jobId().toString())
                .header("Accept", "application/json");
        if (call.operation() == ProvisioningJob.Operation.PROVISION) {
            request.header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(call.body())));
        } else {
            request.DELETE();
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 401) {
                tokens.invalidate(call.audience());
            }
            if (status >= 200 && status < 300) {
                return new Result(status, null);
            }
            return new Result(status, "Product returned HTTP " + status + snippet(response.body()));
        } catch (HttpTimeoutException e) {
            return new Result(null, "Timed out after " + call.timeout().toSeconds() + " s.");
        } catch (IOException e) {
            return new Result(null, "Product unreachable: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, "Interrupted.");
        }
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return ".";
        }
        String flat = body.replaceAll("\\s+", " ").strip();
        return ": " + (flat.length() > MAX_RESPONSE_SNIPPET ? flat.substring(0, MAX_RESPONSE_SNIPPET) + "…" : flat);
    }
}
