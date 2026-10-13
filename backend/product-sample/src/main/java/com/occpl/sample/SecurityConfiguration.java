package com.occpl.sample;

import java.util.List;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two kinds of callers, both with tokens for this product's audience:
 * <ul>
 *   <li>Core's service client ({@code client_id} = {@code sample.provisioning-client-id}) → PROVISIONER,
 *       allowed on {@code /provisioning/**} only;</li>
 *   <li>end users (no {@code client_id} claim) → USER, allowed on {@code /api/**} only. Which company
 *       they may use is decided by Core on every request.</li>
 * </ul>
 * Any other service client gets no authority and is refused everywhere.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String PROVISIONER = "PROVISIONER";
    static final String USER = "USER";

    @Bean
    @Order(1)
    SecurityFilterChain actuator(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(a -> a.requestMatchers(EndpointRequest.to("health", "info")).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http, SampleProperties properties) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/provisioning/**").hasAuthority(PROVISIONER)
                        .requestMatchers("/api/**").hasAuthority(USER)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                    String client = token.getClaimAsString("client_id");
                    List<SimpleGrantedAuthority> authorities;
                    if (client == null) {
                        authorities = List.of(new SimpleGrantedAuthority(USER));
                    } else if (client.equals(properties.provisioningClientId())) {
                        authorities = List.of(new SimpleGrantedAuthority(PROVISIONER));
                    } else {
                        authorities = List.of();
                    }
                    return new JwtAuthenticationToken(token, authorities, token.getSubject());
                })))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }
}
