package com.occpl.platform.identity.internal;

import com.occpl.platform.shared.security.ServicePrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/** Authentication of a product service (client-credentials token); its only authority is {@code SERVICE}. */
class ServiceAuthentication extends AbstractOAuth2TokenAuthenticationToken<Jwt> {

    ServiceAuthentication(Jwt jwt, ServicePrincipal principal) {
        super(jwt, principal, jwt, List.of(new SimpleGrantedAuthority(ServicePrincipal.AUTHORITY)));
        setAuthenticated(true);
    }

    @Override
    public Map<String, Object> getTokenAttributes() {
        return getToken().getClaims();
    }

    @Override
    public String getName() {
        return "service:" + ((ServicePrincipal) getPrincipal()).clientId();
    }
}
