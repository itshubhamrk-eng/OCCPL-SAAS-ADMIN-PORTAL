package com.occpl.platform.shared.security;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import com.occpl.platform.shared.ratelimit.RateLimitFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.config.Customizer;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Secure-by-default HTTP security. The API is a stateless OAuth2 resource server: every request
 * needs a valid bearer token unless explicitly listed below. CSRF protection is disabled because
 * authentication uses the Authorization header, never cookies (SECURITY.md §4).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
class SecurityConfiguration {

    private static final String[] API_DOC_PATHS = {"/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    /** Actuator runs on the internal management port; only health, info and metrics are exposed. */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * OpenAPI JSON and Swagger UI (development/staging only: {@code API_DOCS_ENABLED}). Swagger UI needs
     * scripts, styles and its OAuth2 redirect page, so these paths get their own, looser CSP; the API
     * itself keeps {@code default-src 'none'}.
     */
    @Bean
    @Order(2)
    @ConditionalOnBooleanProperty(name = "springdoc.api-docs.enabled", matchIfMissing = true)
    SecurityFilterChain apiDocsSecurityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        String issuer = environment.getProperty("occpl.openapi.oidc-issuer", "");
        String idpOrigin = issuer.isBlank() ? "" : " " + URI.create(issuer).resolve("/").toString().replaceAll("/$", "");
        http.securityMatcher(API_DOC_PATHS)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; "
                                + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data:; connect-src 'self'" + idpOrigin + "; "
                                + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                        .frameOptions(frame -> frame.deny()));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JsonMapper jsonMapper,
            ObjectProvider<JwtPrincipalResolver> principalResolver,
            ObjectProvider<RateLimitFilter> rateLimitFilter) throws Exception {
        ProblemDetailsAuthenticationHandlers handlers = new ProblemDetailsAuthenticationHandlers(jsonMapper);
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        // Payment gateway webhooks authenticate with an HMAC signature, not a token.
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/payments/**").permitAll()
                        // Public website contact form; rate limited and creates NEW leads only.
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/leads").permitAll()
                        // Product services (client-credentials tokens) may use the service API only, and users never.
                        .requestMatchers("/api/v1/service/**").hasAuthority(ServicePrincipal.AUTHORITY)
                        .anyRequest().access(SecurityConfiguration::userOnly))
                .oauth2ResourceServer(oauth2 -> oauth2
                        // The identity module supplies a converter that maps the token to a platform user.
                        .jwt(jwt -> principalResolver.ifAvailable(r -> jwt.jwtAuthenticationConverter(r::resolve)))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)
                                .maxAgeInSeconds(Duration.ofDays(365).toSeconds()))
                        .frameOptions(frame -> frame.deny()));
        rateLimitFilter.ifAvailable(filter -> http.addFilterAfter(filter, BearerTokenAuthenticationFilter.class));
        return http.build();
    }

    private static AuthorizationResult userOnly(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        boolean user = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getAuthorities().stream().noneMatch(a -> ServicePrincipal.AUTHORITY.equals(a.getAuthority()));
        return new AuthorizationDecision(user);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.IF_MATCH,
                "Idempotency-Key", "X-Correlation-Id"));
        config.setExposedHeaders(List.of(HttpHeaders.ETAG, HttpHeaders.LOCATION, "X-Correlation-Id",
                HttpHeaders.RETRY_AFTER));
        config.setAllowCredentials(false);
        config.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
