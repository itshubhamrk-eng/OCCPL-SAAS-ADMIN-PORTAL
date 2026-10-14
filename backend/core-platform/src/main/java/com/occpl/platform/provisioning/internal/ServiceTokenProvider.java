package com.occpl.platform.provisioning.internal;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access tokens for Core's own service client ({@code occpl-core-services}, client-credentials
 * grant), one per product: the token is requested with {@code scope=<product apiAudience>}, an
 * optional client scope that adds only that product's audience. A product therefore cannot replay
 * the token it receives against another product (SECURITY.md §4). Tokens are cached per audience.
 */
@Component
class ServiceTokenProvider {

    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private final ProvisioningProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private record Cached(String token, Instant expiry) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    ServiceTokenProvider(ProvisioningProperties properties, JsonMapper jsonMapper, Clock clock) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Thrown when no token can be obtained; provisioning treats it as a retryable failure. */
    static class TokenUnavailableException extends Exception {
        TokenUnavailableException(String message) {
            super(message);
        }
    }

    boolean configured() {
        String uri = properties.tokenUri();
        return uri != null && (uri.startsWith("https://") || uri.startsWith("http://"))
                && properties.clientSecret() != null && !properties.clientSecret().isBlank();
    }

    /** A token for the product with this API audience ({@code null}: no product scope requested). */
    String token(String audience) throws TokenUnavailableException {
        String key = audience == null ? "" : audience;
        Cached cached = cache.get(key);
        if (cached != null && clock.instant().isBefore(cached.expiry().minus(REFRESH_MARGIN))) {
            return cached.token();
        }
        if (!configured()) {
            throw new TokenUnavailableException("Service credentials are not configured "
                    + "(PROVISIONING_TOKEN_URI / PROVISIONING_CLIENT_SECRET).");
        }
        String form = "grant_type=client_credentials&client_id=" + encode(properties.clientId())
                + "&client_secret=" + encode(properties.clientSecret())
                + (audience == null ? "" : "&scope=" + encode(audience));
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(properties.tokenUri()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new TokenUnavailableException("Token endpoint returned HTTP " + response.statusCode() + ".");
            }
            JsonNode body = jsonMapper.readTree(response.body());
            String accessToken = body.path("access_token").asString(null);
            if (accessToken == null) {
                throw new TokenUnavailableException("Token endpoint returned no access_token.");
            }
            cache.put(key, new Cached(accessToken, clock.instant().plusSeconds(body.path("expires_in").asLong(60))));
            return accessToken;
        } catch (java.io.IOException e) {
            throw new TokenUnavailableException("Token endpoint unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenUnavailableException("Interrupted while requesting a token.");
        }
    }

    /** Forgets the cached token for this audience (e.g. after the product answered 401). */
    void invalidate(String audience) {
        cache.remove(audience == null ? "" : audience);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
