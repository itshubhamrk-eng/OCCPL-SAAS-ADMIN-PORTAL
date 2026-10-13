package com.occpl.platform.shared.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JsonMapper jsonMapper, Environment environment) throws Exception {
        ProblemDetailsAuthenticationHandlers handlers = new ProblemDetailsAuthenticationHandlers(jsonMapper);
        boolean apiDocsEnabled = environment.getProperty("springdoc.api-docs.enabled", Boolean.class, true);

        http.authorizeHttpRequests(auth -> {
                    if (apiDocsEnabled) {
                        auth.requestMatchers(API_DOC_PATHS).permitAll();
                    }
                    auth.requestMatchers("/error").permitAll();
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
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
        return http.build();
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
