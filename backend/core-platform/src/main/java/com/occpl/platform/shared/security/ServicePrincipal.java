package com.occpl.platform.shared.security;

import java.util.Objects;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A product service calling Core with a client-credentials token (no user involved). Service
 * principals hold only the {@link #AUTHORITY} authority and may call {@code /api/v1/service/**} only.
 *
 * @param clientId the Keycloak client the token was issued to
 */
public record ServicePrincipal(String clientId) {

    public static final String AUTHORITY = "SERVICE";

    public ServicePrincipal {
        Objects.requireNonNull(clientId, "clientId");
    }

    public static Optional<ServicePrincipal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ServicePrincipal service) {
            return Optional.of(service);
        }
        return Optional.empty();
    }
}
