package com.occpl.platform.identity.internal;

import com.occpl.platform.identity.api.CurrentUser;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/** Authentication whose principal is the resolved {@link CurrentUser}; authorities are platform permissions. */
class CurrentUserAuthentication extends AbstractOAuth2TokenAuthenticationToken<Jwt> {

    CurrentUserAuthentication(Jwt jwt, CurrentUser principal, Collection<? extends GrantedAuthority> authorities) {
        super(jwt, principal, jwt, authorities);
        setAuthenticated(true);
    }

    @Override
    public Map<String, Object> getTokenAttributes() {
        return getToken().getClaims();
    }

    @Override
    public String getName() {
        return ((CurrentUser) getPrincipal()).userId().toString();
    }
}
