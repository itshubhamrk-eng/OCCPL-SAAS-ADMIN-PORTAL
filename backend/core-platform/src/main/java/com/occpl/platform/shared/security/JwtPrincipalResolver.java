package com.occpl.platform.shared.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Builds the authentication for a validated JWT (implemented by the identity module). A dedicated
 * type rather than a Spring {@code Converter} bean, which Spring MVC would also register as a
 * request-parameter converter.
 */
@FunctionalInterface
public interface JwtPrincipalResolver {

    AbstractAuthenticationToken resolve(Jwt jwt);
}
