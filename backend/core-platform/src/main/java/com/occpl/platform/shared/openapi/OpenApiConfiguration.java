package com.occpl.platform.shared.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document. When {@code occpl.openapi.oidc-issuer} (the browser-facing Keycloak realm URL) is
 * set, Swagger UI can sign in through Keycloak (authorization code + PKCE) to try the API.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    private static final String BEARER = "bearerAuth";
    private static final String KEYCLOAK = "keycloak";

    @Bean
    OpenAPI coreOpenApi(@Value("${occpl.openapi.oidc-issuer:}") String issuer) {
        Components components = new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
        OpenAPI openApi = new OpenAPI()
                .info(new Info().title("OCCPL Core Platform API").version("v1")
                        .description("Companies, memberships, products, plans, subscriptions and entitlements."))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
        if (!issuer.isBlank()) {
            String base = issuer.replaceAll("/+$", "") + "/protocol/openid-connect";
            components.addSecuritySchemes(KEYCLOAK, new SecurityScheme().type(SecurityScheme.Type.OAUTH2)
                    .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                            .authorizationUrl(base + "/auth").tokenUrl(base + "/token")
                            .scopes(new Scopes().addString("openid", "OpenID Connect")))));
            openApi.addSecurityItem(new SecurityRequirement().addList(KEYCLOAK));
        }
        return openApi.components(components);
    }
}
