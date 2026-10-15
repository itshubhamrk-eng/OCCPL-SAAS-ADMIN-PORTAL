package com.occpl.platform.identity.internal.keycloak;

import com.occpl.platform.identity.api.IdentityProviderAdmin;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enabled when {@code occpl.identity.keycloak.base-url} and {@code client-secret} are set. Without it, features that need the
 * IdP admin API (MFA enrolment, invitations, user disabling) report the IdP as unavailable.
 */
@Configuration(proxyBeanMethods = false)
class KeycloakConfiguration {

    @Bean
    @ConditionalOnExpression("'${occpl.identity.keycloak.base-url:}' != '' and '${occpl.identity.keycloak.client-secret:}' != ''")
    IdentityProviderAdmin keycloakAdminClient(@Value("${occpl.identity.keycloak.base-url}") String baseUrl,
            @Value("${occpl.identity.keycloak.realm:occpl}") String realm,
            @Value("${occpl.identity.keycloak.client-id:occpl-core-admin}") String clientId,
            @Value("${occpl.identity.keycloak.client-secret}") String clientSecret, Clock clock) {
        return new KeycloakAdminClient(baseUrl, realm, clientId, clientSecret, clock);
    }
}
