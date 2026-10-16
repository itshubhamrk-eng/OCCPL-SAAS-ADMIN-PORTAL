package com.occpl.platform.provisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;


import com.occpl.platform.company.api.PrimaryContact;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProvisioningBodyTest {

    @Test
    void sendsPrimaryContactAsAdmin() {
        Map<String, Object> body = new LinkedHashMap<>();
        ProvisioningRunner.addAdmin(body, new PrimaryContact(" Asha Rao ", "asha@acme.test", "9999999999"));
        assertThat(body).containsEntry("adminEmail", "asha@acme.test")
                .containsEntry("adminName", "Asha Rao")
                .containsEntry("adminPhone", "9999999999");
    }

    @Test
    void omitsMissingFieldsAndToleratesNoContact() {
        Map<String, Object> body = new LinkedHashMap<>();
        ProvisioningRunner.addAdmin(body, new PrimaryContact("Asha", "asha@acme.test", " "));
        assertThat(body).containsOnlyKeys("adminEmail", "adminName");

        Map<String, Object> none = new LinkedHashMap<>();
        ProvisioningRunner.addAdmin(none, null);
        assertThat(none).isEmpty();
    }

    @Test
    void sendsOnlyTheProfileFieldsThatAreSet() {
        Map<String, Object> body = new LinkedHashMap<>();
        ProvisioningRunner.addProfile(body, new com.occpl.platform.company.api.CompanyProfile("IN", "https://acme.test",
                "1 Main Rd", null, "Pune", "MH", "411001", "27ABCDE1234F1Z5", null, "https://files/logo.png", null,
                new PrimaryContact("Help desk", "help@acme.test", "+912012345678")));
        assertThat(body).containsEntry("gstNumber", "27ABCDE1234F1Z5").containsEntry("supportEmail", "help@acme.test")
                .containsEntry("logoUrl", "https://files/logo.png").doesNotContainKeys("panNumber", "addressLine2",
                        "faviconUrl");
        Map<String, Object> none = new LinkedHashMap<>();
        ProvisioningRunner.addProfile(none, null);
        assertThat(none).isEmpty();
    }
}
