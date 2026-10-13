package com.occpl.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.occpl.platform.catalog.api.FeatureType;
import com.occpl.platform.catalog.api.ProductCatalog;
import com.occpl.platform.catalog.api.ProductStatus;
import com.occpl.platform.support.MockTokens;
import com.occpl.platform.support.MySqlTestDatabase;
import com.occpl.platform.support.TestData;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Product catalogue: registration, lifecycle, versioned configuration and features. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class CatalogIT {

    private static final UUID PLATFORM_SUPPORT_ROLE = UUID.fromString("01920000-0000-7000-8000-000000000002");
    private static final String VALID_CONFIG = """
            {"schemaVersion":1,"launch":{"path":"/app/{companyCode}"},
             "provisioning":{"mode":"SYNC","path":"/provisioning/v1/tenants/{companyId}","timeoutSeconds":20}}""";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired ProductCatalog catalog;
    @MockitoBean JwtDecoder jwtDecoder;

    TestData data;
    String run;

    @BeforeEach
    void setUp() throws Exception {
        MockTokens tokens = new MockTokens(jwtDecoder);
        data = new TestData(jdbc);
        run = UUID.randomUUID().toString().substring(0, 6);
        for (String name : new String[] {"root", "support", "plain"}) {
            tokens.register(name, name + "-" + run + "@occpl.test", true);
            perform(get("/api/v1/me"), name, null).andExpect(status().isOk());
        }
        data.platformRole(data.userIdByEmail("root-" + run + "@occpl.test"), TestData.SUPER_ADMIN_ROLE);
        data.platformRole(data.userIdByEmail("support-" + run + "@occpl.test"), PLATFORM_SUPPORT_ROLE);
    }

    @Test
    void registersProductsWithValidationAndAuthorization() throws Exception {
        perform(post("/api/v1/products"), "root",
                "{\"code\":\"Bad Code\",\"name\":\"\",\"appUrl\":\"http://inventory.example.com\"}")
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/products"), "root",
                "{\"code\":\"inv-" + run + "\",\"name\":\"Inventory\",\"appUrl\":\"http://inventory.example.com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("appUrl"));

        JsonNode product = register("inv");
        assertThat(product.get("status").asString()).isEqualTo("DRAFT");
        assertThat(product.get("apiAudience").asString()).isEqualTo("product-inv-" + run);
        assertThat(product.get("appUrl").asString()).isEqualTo("https://inv-" + run + ".occpl.test");

        perform(post("/api/v1/products"), "root", registerBody("inv", null)).andExpect(status().isConflict());
        perform(post("/api/v1/products"), "root",
                "{\"code\":\"other-" + run + "\",\"name\":\"Other\",\"appUrl\":\"https://o.occpl.test\","
                        + "\"apiAudience\":\"product-inv-" + run + "\"}")
                .andExpect(status().isConflict());
        perform(post("/api/v1/products"), "support", registerBody("nope", null)).andExpect(status().isForbidden());
        perform(get("/api/v1/products/" + product.get("id").asString()), "support", null).andExpect(status().isOk());
        perform(get("/api/v1/products"), "plain", null).andExpect(status().isForbidden());

        perform(get("/api/v1/products?q=inv-" + run), "support", null).andExpect(jsonPath("$.totalItems").value(1));
        perform(get("/api/v1/products?status=ACTIVE&q=inv-" + run), "support", null)
                .andExpect(jsonPath("$.totalItems").value(0));
        perform(get("/api/v1/products?sort=apiAudience"), "support", null).andExpect(status().isBadRequest());
    }

    @Test
    void updatesProductKeepingCodeImmutable() throws Exception {
        String id = register("upd").get("id").asString();
        perform(patch("/api/v1/products/" + id), "root",
                "{\"name\":\"Inventory Pro\",\"apiBaseUrl\":\"https://api.upd.occpl.test/\",\"code\":\"hacked\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Inventory Pro"))
                .andExpect(jsonPath("$.code").value("upd-" + run))
                .andExpect(jsonPath("$.apiBaseUrl").value("https://api.upd.occpl.test"))
                .andExpect(header().string("ETag", "\"1\""));
        perform(patch("/api/v1/products/" + id).header("If-Match", "\"0\""), "root", "{\"name\":\"Stale\"}")
                .andExpect(status().isPreconditionFailed());
        perform(patch("/api/v1/products/" + id), "root", "{\"apiBaseUrl\":\"ftp://x.occpl.test\"}")
                .andExpect(status().isBadRequest());
        perform(patch("/api/v1/products/" + id), "root", "{\"apiBaseUrl\":\"\"}")
                .andExpect(jsonPath("$.apiBaseUrl").doesNotExist());
    }

    @Test
    void versionsAreValidatedImmutableAndPublishable() throws Exception {
        String id = register("ver").get("id").asString();
        String base = "/api/v1/products/" + id;

        perform(post(base + "/activate"), "root", null).andExpect(status().isUnprocessableContent());
        perform(post(base + "/versions"), "root", "{\"config\":{\"schemaVersion\":1,\"provisioning\":{\"mode\":\"SYNC\"}}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").exists());
        perform(post(base + "/versions"), "root", "{\"config\":{\"schemaVersion\":1,\"provisioning\":{\"mode\":\"NONE\"},"
                + "\"metadata\":{\"serviceToken\":\"abc\"}}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("noSecrets"));

        perform(post(base + "/versions"), "root", "{\"config\":" + VALID_CONFIG + ",\"notes\":\"Initial\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", base + "/versions/1"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        // Same content with different key order is still identical.
        perform(post(base + "/versions"), "root", "{\"config\":{\"provisioning\":{\"timeoutSeconds\":20,"
                + "\"path\":\"/provisioning/v1/tenants/{companyId}\",\"mode\":\"SYNC\"},\"launch\":{\"path\":\"/app/{companyCode}\"},"
                + "\"schemaVersion\":1}}")
                .andExpect(status().isConflict());

        perform(post(base + "/versions/1/publish"), "root", null).andExpect(jsonPath("$.current").value(true));
        perform(post(base + "/activate"), "root", null).andExpect(jsonPath("$.status").value("ACTIVE"));

        perform(post(base + "/versions"), "root", "{\"config\":{\"schemaVersion\":1,\"provisioning\":{\"mode\":\"ASYNC\","
                + "\"path\":\"/provisioning/v1/tenants/{companyId}\"}}}").andExpect(status().isCreated());
        perform(post(base + "/versions/2/publish"), "root", null).andExpect(status().isOk());
        perform(get(base), "root", null).andExpect(jsonPath("$.currentVersionNo").value(2));
        assertThat(catalog.currentConfig(UUID.fromString(id)).orElseThrow().config().at("/provisioning/mode").asString())
                .isEqualTo("ASYNC");

        // Rollback by publishing version 1 again.
        perform(post(base + "/versions/1/publish"), "root", null).andExpect(status().isOk());
        perform(get(base + "/versions"), "support", null)
                .andExpect(jsonPath("$[0].versionNo").value(2))
                .andExpect(jsonPath("$[0].current").value(false))
                .andExpect(jsonPath("$[1].current").value(true))
                .andExpect(jsonPath("$[1].config").doesNotExist());
        perform(get(base + "/versions/1"), "support", null)
                .andExpect(jsonPath("$.config.provisioning.mode").value("SYNC"));
        perform(delete(base + "/versions/2"), "root", null).andExpect(status().isConflict());

        perform(post(base + "/versions"), "root", "{\"config\":{\"schemaVersion\":1,\"provisioning\":{\"mode\":\"NONE\"}}}")
                .andExpect(jsonPath("$.versionNo").value(3));
        perform(delete(base + "/versions/3"), "root", null).andExpect(status().isNoContent());
        perform(get(base + "/versions/3"), "root", null).andExpect(status().isNotFound());

        perform(post(base + "/retire"), "root", "{\"reason\":\"Replaced\"}").andExpect(jsonPath("$.status").value("RETIRED"));
        perform(post(base + "/retire"), "root", null).andExpect(status().isUnprocessableContent());
        perform(post(base + "/activate"), "root", null).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where target_type = 'product' and target_id = ?",
                Integer.class, id)).isGreaterThanOrEqualTo(9);

        perform(get("/api/v1/products/config-schema"), "support", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.$id").value("https://api.occpl.com/schemas/product-config/v1"));
    }

    @Test
    void featuresHaveConsistentShapesAndLifecycle() throws Exception {
        String id = register("feat").get("id").asString();
        String base = "/api/v1/products/" + id + "/features";

        perform(post(base), "root", "{\"code\":\"export\",\"name\":\"Export\",\"type\":\"BOOLEAN\",\"unit\":\"COUNT\"}")
                .andExpect(status().isBadRequest());
        perform(post(base), "root", "{\"code\":\"calls\",\"name\":\"API calls\",\"type\":\"LIMIT\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        perform(post(base), "root", "{\"code\":\"seats\",\"name\":\"Seats\",\"type\":\"LIMIT\",\"unit\":\"SEATS\","
                + "\"resetPeriod\":\"MONTHLY\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("stockUnit"));
        perform(post(base), "root", "{\"code\":\"Bad Code\",\"name\":\"x\",\"type\":\"BOOLEAN\"}")
                .andExpect(status().isBadRequest());

        perform(post(base), "root", "{\"code\":\"seats\",\"name\":\"Seats\",\"type\":\"LIMIT\",\"unit\":\"SEATS\","
                + "\"resetPeriod\":\"NONE\"}").andExpect(status().isCreated());
        String callsId = body(perform(post(base), "root", "{\"code\":\"api.calls\",\"name\":\"API calls\","
                + "\"type\":\"LIMIT\",\"unit\":\"API_CALLS\",\"resetPeriod\":\"MONTHLY\"}")
                .andExpect(status().isCreated())).get("id").asString();
        perform(post(base), "root", "{\"code\":\"export\",\"name\":\"Export\",\"type\":\"BOOLEAN\"}")
                .andExpect(status().isCreated());
        perform(post(base), "root", "{\"code\":\"export\",\"name\":\"Again\",\"type\":\"BOOLEAN\"}")
                .andExpect(status().isConflict());

        perform(patch(base + "/" + callsId), "root", "{\"name\":\"API requests\",\"type\":\"BOOLEAN\"}")
                .andExpect(jsonPath("$.name").value("API requests"))
                .andExpect(jsonPath("$.type").value("LIMIT"));
        perform(post(base + "/" + callsId + "/deprecate"), "root", null).andExpect(jsonPath("$.status").value("DEPRECATED"));
        perform(get(base + "?status=ACTIVE"), "support", null).andExpect(jsonPath("$.length()").value(2));
        perform(get(base), "support", null).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("api.calls"));
        perform(post(base + "/" + callsId + "/reactivate"), "root", null).andExpect(jsonPath("$.status").value("ACTIVE"));
        perform(post(base + "/" + UUID.randomUUID() + "/deprecate"), "root", null).andExpect(status().isNotFound());

        assertThat(catalog.features(UUID.fromString(id))).hasSize(3)
                .anyMatch(f -> f.code().equals("seats") && f.type() == FeatureType.LIMIT);
        assertThat(catalog.findByCode("feat-" + run).orElseThrow().status()).isEqualTo(ProductStatus.DRAFT);
    }

    private String registerBody(String code, String audience) {
        return "{\"code\":\"" + code + "-" + run + "\",\"name\":\"" + code + "\",\"description\":\"Test product\","
                + "\"appUrl\":\"https://" + code + "-" + run + ".occpl.test/\""
                + (audience == null ? "" : ",\"apiAudience\":\"" + audience + "\"") + "}";
    }

    private JsonNode register(String code) throws Exception {
        return body(perform(post("/api/v1/products"), "root", registerBody(code, null)).andExpect(status().isCreated()));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
