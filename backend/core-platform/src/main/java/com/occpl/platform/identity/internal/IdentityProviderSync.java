package com.occpl.platform.identity.internal;

import com.occpl.platform.identity.api.IdentityProviderAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mirrors user enable/disable into Keycloak after commit. Core already enforces the new status on
 * every request, so a failure here is logged for operators rather than rolled back.
 */
@Component
class IdentityProviderSync {

    private static final Logger log = LoggerFactory.getLogger(IdentityProviderSync.class);

    private final ObjectProvider<IdentityProviderAdmin> idp;
    private final UserIdentityRepository identities;
    private final IdentityProperties properties;

    IdentityProviderSync(ObjectProvider<IdentityProviderAdmin> idp, UserIdentityRepository identities,
            IdentityProperties properties) {
        this.idp = idp;
        this.identities = identities;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onUserAccessChanged(UserAccessChanged event) {
        IdentityProviderAdmin admin = idp.getIfAvailable();
        if (admin == null) {
            log.warn("Keycloak admin client not configured; user {} enabled={} not synchronised", event.userId(),
                    event.enabled());
            return;
        }
        identities.findByUserIdAndProvider(event.userId(), properties.providerId()).ifPresent(identity -> {
            try {
                admin.setEnabled(identity.subject(), event.enabled());
                if (!event.enabled()) {
                    admin.logout(identity.subject());
                }
            } catch (IdentityProviderAdmin.IdentityProviderException e) {
                log.error("Failed to synchronise user {} enabled={} to Keycloak (HTTP {})", event.userId(),
                        event.enabled(), e.status(), e);
            }
        });
    }
}
