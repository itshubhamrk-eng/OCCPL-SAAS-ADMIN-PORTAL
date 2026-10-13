package com.occpl.platform.identity.internal;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Decides whether a token proves multi-factor authentication, based on its {@code amr}
 * (Authentication Methods References, RFC 8176) claim. Keycloak emits it through the AMR protocol
 * mapper configured on the portal client (infra/docker/keycloak/occpl-realm.json).
 */
@Component
class MfaPolicy {

    private final IdentityProperties properties;

    MfaPolicy(IdentityProperties properties) {
        this.properties = properties;
    }

    boolean required() {
        return properties.mfa().platformRequired();
    }

    boolean satisfiedBy(Jwt jwt) {
        List<String> amr = jwt.getClaimAsStringList("amr");
        return amr != null && amr.stream().anyMatch(properties.mfa().acceptedAmr()::contains);
    }
}
