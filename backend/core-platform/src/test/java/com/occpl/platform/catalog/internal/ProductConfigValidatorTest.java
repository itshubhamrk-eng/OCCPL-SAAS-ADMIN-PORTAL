package com.occpl.platform.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.occpl.platform.shared.error.Problems.FieldViolation;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProductConfigValidatorTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ProductConfigValidator validator = new ProductConfigValidator(json);

    private List<FieldViolation> validate(String config) {
        return validator.validate(json.readTree(config));
    }

    @Test
    void acceptsCompleteValidConfiguration() {
        assertThat(validate("""
                {"schemaVersion":1,
                 "launch":{"path":"/app/{companyCode}","openInNewTab":true},
                 "provisioning":{"mode":"SYNC","path":"/provisioning/v1/tenants/{companyId}","timeoutSeconds":30,"maxAttempts":5},
                 "tenantSettings":[{"key":"region","type":"enum","label":"Region","options":["in","eu"],"default":"in"},
                                   {"key":"maxBranches","type":"number","label":"Branches","default":5}],
                 "metadata":{"owner":"inventory-team"}}""")).isEmpty();
        assertThat(validate("{\"schemaVersion\":1,\"provisioning\":{\"mode\":\"NONE\"}}")).isEmpty();
    }

    @Test
    void reportsSchemaViolationsWithFieldPaths() {
        List<FieldViolation> violations = validate("""
                {"schemaVersion":2,"provisioning":{"mode":"SYNC"},"unknownTopLevel":true}""");
        assertThat(violations).extracting(FieldViolation::code).contains("const", "additionalProperties");
        assertThat(validate("{\"schemaVersion\":1,\"provisioning\":{\"mode\":\"SYNC\"}}"))
                .extracting(FieldViolation::field).anyMatch(f -> f.startsWith("config.provisioning"));
        assertThat(validate("{\"schemaVersion\":1}")).extracting(FieldViolation::code).contains("required");
        assertThat(validate("[]")).isNotEmpty();
    }

    @Test
    void rejectsSecrets() {
        assertThat(validate("""
                {"schemaVersion":1,"provisioning":{"mode":"NONE"},"metadata":{"clientSecret":"abc"}}"""))
                .extracting(FieldViolation::code).containsExactly("noSecrets");
        assertThat(validate("""
                {"schemaVersion":1,"provisioning":{"mode":"NONE"},"metadata":{"note":"-----BEGIN RSA PRIVATE KEY----- x"}}"""))
                .extracting(FieldViolation::code).containsExactly("noSecrets");
        assertThat(validate("""
                {"schemaVersion":1,"provisioning":{"mode":"NONE"},
                 "tenantSettings":[{"key":"apiToken","type":"string","label":"Token"}]}"""))
                .extracting(FieldViolation::code).containsExactly("noSecrets");
    }

    @Test
    void checksTenantSettingConsistency() {
        List<FieldViolation> violations = validate("""
                {"schemaVersion":1,"provisioning":{"mode":"NONE"},"tenantSettings":[
                  {"key":"color","type":"enum","label":"Colour"},
                  {"key":"color","type":"string","label":"Again","options":["x"]},
                  {"key":"size","type":"number","label":"Size","default":"big"},
                  {"key":"plan","type":"enum","label":"Plan","options":["a","b"],"default":"c"}]}""");
        assertThat(violations).extracting(FieldViolation::code)
                .containsExactlyInAnyOrder("required", "unique", "notAllowed", "type", "type");
    }

    @Test
    void publishesTheSchemaDocument() {
        assertThat(validator.schemaJson()).contains("\"$id\": \"https://api.occpl.com/schemas/product-config/v1\"");
    }
}
