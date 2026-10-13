package com.occpl.platform.identity.api;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Administrative operations on the OIDC identity provider (Keycloak). Implementations call the IdP
 * synchronously; callers must not hold database transactions open across these calls.
 */
public interface IdentityProviderAdmin {

    /** Creates an enabled user with an unverified email and the given required actions; returns the subject. */
    String createUser(String email, String firstName, String lastName, Set<RequiredAction> requiredActions);

    Optional<IdpUser> findByEmail(String email);

    Optional<IdpUser> findBySubject(String subject);

    void addRequiredActions(String subject, Set<RequiredAction> actions);

    boolean hasCredential(String subject, String credentialType);

    void setEnabled(String subject, boolean enabled);

    /** Ends all sessions of the user (refresh tokens become invalid). */
    void logout(String subject);

    /** Emails the user a link to complete the given actions (requires SMTP configured in the IdP). */
    void sendActionsEmail(String subject, Set<RequiredAction> actions, Duration lifespan, String clientId,
            String redirectUri);

    record IdpUser(String subject, String email, boolean enabled, boolean emailVerified, Set<String> requiredActions) {
    }

    enum RequiredAction {
        VERIFY_EMAIL, UPDATE_PASSWORD, CONFIGURE_TOTP, UPDATE_PROFILE
    }

    /** The IdP rejected or failed a request. {@code status} is the HTTP status, or 0 if unreachable. */
    class IdentityProviderException extends RuntimeException {

        private final int status;

        public IdentityProviderException(String message, int status, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
