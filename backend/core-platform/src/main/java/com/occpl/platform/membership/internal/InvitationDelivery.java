package com.occpl.platform.membership.internal;

import com.occpl.platform.identity.api.IdentityProviderAdmin;
import com.occpl.platform.identity.api.IdentityProviderAdmin.IdpUser;
import com.occpl.platform.identity.api.IdentityProviderAdmin.RequiredAction;
import com.occpl.platform.notification.api.Notifications;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers an invitation after its transaction commits, choosing one email per invitee:
 * <ul>
 *   <li>no usable sign-in account → Keycloak creates the account and emails a "set your password"
 *       link that returns to the accept page (this also verifies the email address);</li>
 *   <li>existing account → the platform's own invitation email with the accept link.</li>
 * </ul>
 * Failures are logged and counted; the invitation stays PENDING and can be re-sent.
 */
@Component
class InvitationDelivery {

    private static final Logger log = LoggerFactory.getLogger(InvitationDelivery.class);
    private static final Set<RequiredAction> ACCOUNT_SETUP = Set.of(RequiredAction.UPDATE_PASSWORD,
            RequiredAction.VERIFY_EMAIL);

    private final ObjectProvider<IdentityProviderAdmin> idp;
    private final Notifications notifications;
    private final InvitationProperties properties;
    private final TransactionTemplate newTransaction;
    private final MeterRegistry meters;
    private final Clock clock;

    InvitationDelivery(ObjectProvider<IdentityProviderAdmin> idp, Notifications notifications,
            InvitationProperties properties, PlatformTransactionManager transactionManager, MeterRegistry meters,
            Clock clock) {
        this.idp = idp;
        this.notifications = notifications;
        this.properties = properties;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meters = meters;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onInvitationIssued(InvitationIssued event) {
        String acceptLink = properties.acceptUrl() + "?token=" + URLEncoder.encode(event.token(), StandardCharsets.UTF_8);
        try {
            IdentityProviderAdmin admin = idp.getIfAvailable();
            if (admin == null) {
                log.warn("Keycloak admin client not configured; sending platform invitation email only for {}",
                        event.invitationId());
                sendPlatformEmail(event, acceptLink);
                return;
            }
            Optional<IdpUser> account = admin.findByEmail(event.email());
            if (account.isEmpty() || account.get().requiredActions().contains(RequiredAction.UPDATE_PASSWORD.name())) {
                String subject = account.map(IdpUser::subject).orElseGet(() ->
                        admin.createUser(event.email(), event.firstName(), event.lastName(), ACCOUNT_SETUP));
                Duration lifespan = Duration.between(clock.instant(), event.expiresAt());
                admin.sendActionsEmail(subject, ACCOUNT_SETUP, lifespan, properties.portalClientId(), acceptLink);
                meters.counter("occpl.invitations.delivered", "channel", "account-setup").increment();
            } else {
                sendPlatformEmail(event, acceptLink);
            }
        } catch (RuntimeException e) {
            meters.counter("occpl.invitations.delivery_failures").increment();
            log.error("Failed to deliver invitation {}; it can be re-sent", event.invitationId(), e);
        }
    }

    private void sendPlatformEmail(InvitationIssued event, String acceptLink) {
        newTransaction.executeWithoutResult(status ->
                notifications.enqueue(InvitationEmails.invitation(event, acceptLink)));
        meters.counter("occpl.invitations.delivered", "channel", "platform-email").increment();
    }
}
