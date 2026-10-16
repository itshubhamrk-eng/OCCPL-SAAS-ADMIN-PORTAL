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
}
