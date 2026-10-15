package com.occpl.platform.identity;

import com.occpl.platform.support.KeycloakTestServer;

/** Public entry point to {@link KeycloakLoginFlow} for tests in other packages. */
public class KeycloakLoginFlowAccess {

    public String completeActionsEmail(String link, String newPassword) throws Exception {
        return new KeycloakLoginFlow(KeycloakTestServer.realmUrl(), "occpl-admin-portal")
                .completeActionsEmail(link, newPassword);
    }
}
