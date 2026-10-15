package com.occpl.platform.identity.internal;

import com.occpl.platform.identity.api.IdentityProviderAdmin;
import com.occpl.platform.identity.api.IdentityProviderAdmin.RequiredAction;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * After platform roles are granted, asks Keycloak to make the user set up an authenticator app at
 * their next login. Platform permissions stay withheld until a token proves MFA (see
 * {@link MfaPolicy}), so a failure here degrades usability, never security; it is logged for ops.
 */
@Component
class MfaEnrolmentListener {

    private static final Logger log = LoggerFactory.getLogger(MfaEnrolmentListener.class);

    private final ObjectProvider<IdentityProviderAdmin> idp;
    private final UserIdentityRepository identities;
    private final IdentityProperties properties;

    MfaEnrolmentListener(ObjectProvider<IdentityProviderAdmin> idp, UserIdentityRepository identities,
            IdentityProperties properties) {
        this.idp = idp;
        this.identities = identities;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onPlatformRolesGranted(PlatformRolesGranted event) {
        if (!properties.mfa().platformRequired()) {
            return;
        }
        IdentityProviderAdmin admin = idp.getIfAvailable();
        if (admin == null) {
            log.warn("Cannot require MFA enrolment for user {}: Keycloak admin client not configured", event.userId());
            return;
        }
        identities.findByUserIdAndProvider(event.userId(), properties.providerId()).ifPresent(identity -> {
            try {
                if (!admin.hasCredential(identity.subject(), "otp")) {
                    admin.addRequiredActions(identity.subject(), Set.of(RequiredAction.CONFIGURE_TOTP));
                    log.info("Required authenticator setup for user {}", event.userId());
                }
            } catch (IdentityProviderAdmin.IdentityProviderException e) {
                log.error("Failed to require MFA enrolment for user {} (HTTP {})", event.userId(), e.status(), e);
            }
        });
    }
}
