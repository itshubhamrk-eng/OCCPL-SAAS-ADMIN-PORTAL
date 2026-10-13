package com.occpl.platform.membership.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param acceptUrl portal page that completes an invitation ({@code ?token=} is appended)
 * @param validity how long an invitation can be accepted
 * @param portalClientId Keycloak client used for the account-setup email redirect
 */
@ConfigurationProperties("occpl.invitations")
record InvitationProperties(@DefaultValue("http://localhost:4200/invitations/accept") String acceptUrl,
        @DefaultValue("7d") Duration validity, @DefaultValue("occpl-admin-portal") String portalClientId) {
}
