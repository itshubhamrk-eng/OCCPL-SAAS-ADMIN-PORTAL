package com.occpl.platform.catalog.internal;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.occpl.platform.shared.error.Problems.FieldViolation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates product configuration against the published JSON Schema
 * ({@code catalog/product-config-v1.schema.json}) plus rules a schema cannot express: unique setting
 * keys, consistent defaults and options, and <b>no secrets</b> (service credentials belong in
 * Keycloak and the secret store, never in configuration that platform staff can read).
 */
@Component
class ProductConfigValidator {

    static final int CURRENT_SCHEMA_VERSION = 1;
    private static final String SCHEMA_RESOURCE = "catalog/product-config-v1.schema.json";
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(secret|passw(or)?d|passwd|token|api[_.-]?key|private[_.-]?key|credential|client[_.-]?secret).*");
    private static final Pattern SECRET_VALUE = Pattern.compile("(?s).*(-----BEGIN [A-Z ]*PRIVATE KEY-----|\\bey[A-Za-z0-9_-]{10,}\\.ey[A-Za-z0-9_-]{10,}\\.).*");

    private final Schema schema;
    private final String schemaJson;

    ProductConfigValidator(JsonMapper jsonMapper) {
        ClassPathResource resource = new ClassPathResource(SCHEMA_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            this.schemaJson = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + SCHEMA_RESOURCE, e);
        }
        this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaJson);
    }

    String schemaJson() {
        return schemaJson;
    }

    List<FieldViolation> validate(JsonNode config) {
        List<FieldViolation> violations = new ArrayList<>();
        if (config == null || !config.isObject()) {
            violations.add(new FieldViolation("config", "type", "must be a JSON object"));
            return violations;
        }
        schema.validate(config).forEach(error -> violations.add(new FieldViolation(
                "config" + pointer(error.getInstanceLocation().toString()), error.getKeyword(), error.getMessage())));
        if (!violations.isEmpty()) {
            return violations;
        }
        checkTenantSettings(config.path("tenantSettings"), violations);
        checkMetadata(config.path("metadata"), violations);
        return violations;
    }

    private static void checkTenantSettings(JsonNode settings, List<FieldViolation> violations) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < settings.size(); i++) {
            JsonNode setting = settings.get(i);
            String path = "config.tenantSettings[" + i + "]";
            String key = setting.path("key").asString();
            if (!keys.add(key)) {
                violations.add(new FieldViolation(path + ".key", "unique", "duplicate setting key '" + key + "'"));
            }
            if (SECRET_NAME.matcher(key).matches()) {
                violations.add(secret(path + ".key"));
            }
            String type = setting.path("type").asString();
            JsonNode options = setting.path("options");
            JsonNode defaultValue = setting.get("default");
            if (type.equals("enum") && options.isEmpty()) {
                violations.add(new FieldViolation(path + ".options", "required", "enum settings need options"));
            }
            if (!type.equals("enum") && !options.isMissingNode()) {
                violations.add(new FieldViolation(path + ".options", "notAllowed", "options are only allowed for enum settings"));
            }
            if (defaultValue != null && !defaultMatches(type, defaultValue, options)) {
                violations.add(new FieldViolation(path + ".default", "type", "default does not match the setting type or options"));
            }
        }
    }

    private static void checkMetadata(JsonNode metadata, List<FieldViolation> violations) {
        for (Map.Entry<String, JsonNode> entry : metadata.properties()) {
            String path = "config.metadata." + entry.getKey();
            if (SECRET_NAME.matcher(entry.getKey()).matches() || SECRET_VALUE.matcher(entry.getValue().asString()).matches()) {
                violations.add(secret(path));
            }
        }
    }

    private static boolean defaultMatches(String type, JsonNode value, JsonNode options) {
        return switch (type) {
            case "string" -> value.isString();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "enum" -> value.isString() && options.valueStream().anyMatch(o -> o.asString().equals(value.asString()));
            default -> false;
        };
    }

    private static FieldViolation secret(String path) {
        return new FieldViolation(path, "noSecrets",
                "secrets must not be stored in product configuration; use Keycloak or the secret store");
    }

    /** Turns a JSON Pointer such as {@code /provisioning/mode} into {@code .provisioning.mode}. */
    private static String pointer(String location) {
        String path = location.startsWith("$") ? location.substring(1) : location;
        return path.replace('/', '.').replaceAll("\\.(\\d+)(?=\\.|$)", "[$1]");
    }
}
