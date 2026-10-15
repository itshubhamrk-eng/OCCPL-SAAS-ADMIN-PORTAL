package com.occpl.platform.identity.internal.keycloak;

import com.occpl.platform.identity.api.IdentityProviderAdmin;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Keycloak Admin REST API client. Authenticates as the confidential {@code occpl-core-admin} client
 * (client-credentials grant), whose service account holds only realm-management
 * {@code manage-users}, {@code view-users} and {@code query-users}.
 */
public class KeycloakAdminClient implements IdentityProviderAdmin {

    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(30);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    private volatile String accessToken;
    private volatile Instant accessTokenExpiry = Instant.EPOCH;

    public KeycloakAdminClient(String baseUrl, String realm, String clientId, String clientSecret, Clock clock) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(clientSecret)) {
            throw new IllegalArgumentException("Keycloak base URL and client secret are required");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", "")).requestFactory(requestFactory).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    @Override
    public String createUser(String email, String firstName, String lastName, Set<RequiredAction> requiredActions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", email);
        body.put("email", email);
        body.put("enabled", true);
        body.put("emailVerified", false);
        if (firstName != null) {
            body.put("firstName", firstName);
        }
        if (lastName != null) {
            body.put("lastName", lastName);
        }
        body.put("requiredActions", names(requiredActions));
        URI location = call("create user", () -> http.post().uri("/admin/realms/{realm}/users", realm)
                .headers(this::authorize).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity().getHeaders().getLocation());
        if (location == null) {
            throw new IdentityProviderException("Keycloak did not return the created user's location", 502, null);
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public Optional<IdpUser> findByEmail(String email) {
        List<Map<String, Object>> users = call("find user", () -> http.get()
                .uri(b -> b.path("/admin/realms/{realm}/users").queryParam("email", email)
                        .queryParam("exact", true).queryParam("briefRepresentation", false).build(realm))
                .headers(this::authorize).retrieve().body(LIST_OF_MAPS));
        return users == null ? Optional.empty() : users.stream().findFirst().map(KeycloakAdminClient::toUser);
    }

    @Override
    public Optional<IdpUser> findBySubject(String subject) {
        try {
            return Optional.ofNullable(userRepresentation(subject)).map(KeycloakAdminClient::toUser);
        } catch (IdentityProviderException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void addRequiredActions(String subject, Set<RequiredAction> actions) {
        Map<String, Object> user = userRepresentation(subject);
        Set<String> merged = new HashSet<>(stringList(user.get("requiredActions")));
        merged.addAll(names(actions));
        updateUser(subject, Map.of("requiredActions", List.copyOf(merged)));
    }

    @Override
    public boolean hasCredential(String subject, String credentialType) {
        List<Map<String, Object>> credentials = call("list credentials", () -> http.get()
                .uri("/admin/realms/{realm}/users/{id}/credentials", realm, subject)
                .headers(this::authorize).retrieve().body(LIST_OF_MAPS));
        return credentials != null && credentials.stream().anyMatch(c -> credentialType.equals(c.get("type")));
    }

    @Override
    public void setEnabled(String subject, boolean enabled) {
        updateUser(subject, Map.of("enabled", enabled));
    }

    @Override
    public void logout(String subject) {
        call("logout user", () -> http.post().uri("/admin/realms/{realm}/users/{id}/logout", realm, subject)
                .headers(this::authorize).retrieve().toBodilessEntity());
    }

    @Override
    public void sendActionsEmail(String subject, Set<RequiredAction> actions, Duration lifespan, String clientId,
            String redirectUri) {
        call("send actions email", () -> http.put()
                .uri(b -> b.path("/admin/realms/{realm}/users/{id}/execute-actions-email")
                        .queryParam("client_id", clientId).queryParam("redirect_uri", redirectUri)
                        .queryParam("lifespan", lifespan.toSeconds()).build(realm, subject))
                .headers(this::authorize).contentType(MediaType.APPLICATION_JSON).body(names(actions))
                .retrieve().toBodilessEntity());
    }

    private Map<String, Object> userRepresentation(String subject) {
        return call("get user", () -> http.get().uri("/admin/realms/{realm}/users/{id}", realm, subject)
                .headers(this::authorize).retrieve().body(MAP));
    }

    private void updateUser(String subject, Map<String, Object> changes) {
        call("update user", () -> http.put().uri("/admin/realms/{realm}/users/{id}", realm, subject)
                .headers(this::authorize).contentType(MediaType.APPLICATION_JSON).body(changes)
                .retrieve().toBodilessEntity());
    }

    private void authorize(HttpHeaders headers) {
        headers.setBearerAuth(serviceToken());
    }

    private synchronized String serviceToken() {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenExpiry.minus(TOKEN_REFRESH_MARGIN))) {
            return accessToken;
        }
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<String, Object> response = call("obtain service token", () -> http.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(MAP));
        if (response == null || !(response.get("access_token") instanceof String token)) {
            throw new IdentityProviderException("Keycloak token response had no access token", 502, null);
        }
        long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 60;
        accessToken = token;
        accessTokenExpiry = now.plusSeconds(expiresIn);
        return token;
    }

    private <T> T call(String operation, java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 401) {
                accessToken = null; // force a fresh service token next time
            }
            // The response body is not included: it may contain user data.
            throw new IdentityProviderException("Keycloak " + operation + " failed with HTTP " + status.value(),
                    status.value(), e);
        } catch (ResourceAccessException e) {
            throw new IdentityProviderException("Keycloak unreachable during " + operation, 0, e);
        }
    }

    private static IdpUser toUser(Map<String, Object> rep) {
        return new IdpUser((String) rep.get("id"), (String) rep.get("email"), Boolean.TRUE.equals(rep.get("enabled")),
                Boolean.TRUE.equals(rep.get("emailVerified")), Set.copyOf(stringList(rep.get("requiredActions"))));
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static List<String> names(Set<RequiredAction> actions) {
        return actions.stream().map(Enum::name).sorted().collect(Collectors.toList());
    }
}
