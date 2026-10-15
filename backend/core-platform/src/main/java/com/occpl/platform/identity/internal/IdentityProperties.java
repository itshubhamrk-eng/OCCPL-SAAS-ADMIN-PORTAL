package com.occpl.platform.identity.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param providerId stored in {@code user_identity.provider} for subjects from the configured issuer
 * @param bootstrapSuperAdminEmail verified email that receives SUPER_ADMIN on first login while no
 *        Super Admin exists; leave empty after the platform is initialised
 */
@ConfigurationProperties("occpl.identity")
record IdentityProperties(@DefaultValue("keycloak") String providerId, String bootstrapSuperAdminEmail,
        @DefaultValue Mfa mfa) {

    /**
     * @param platformRequired withhold platform permissions unless the token's {@code amr} claim
     *        contains one of {@code acceptedAmr}
     */
    record Mfa(@DefaultValue("true") boolean platformRequired,
            @DefaultValue({"otp", "hwk", "mfa"}) List<String> acceptedAmr) {
    }
}
