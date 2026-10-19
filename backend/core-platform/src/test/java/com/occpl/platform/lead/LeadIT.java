package com.occpl.platform.lead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Lead registration: validation, authorization, editing and the status flow. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class LeadIT {

    private static final UUID PLATFORM_SUPPORT_ROLE = UUID.fromString("01920000-0000-7000-8000-000000000002");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoBean JwtDecoder jwtDecoder;

    String run;

    @BeforeEach
    void setUp() throws Exception {
        MockTokens tokens = new MockTokens(jwtDecoder);
        TestData data = new TestData(jdbc);
        run = UUID.randomUUID().toString().substring(0, 6);
        for (String name : new String[] {"root", "support", "plain"}) {
            tokens.register(name, name + "-" + run + "@occpl.test", true);
            perform(get("/api/v1/me"), name, null).andExpect(status().isOk());
        }
        data.platformRole(data.userIdByEmail("root-" + run + "@occpl.test"), TestData.SUPER_ADMIN_ROLE);
        data.platformRole(data.userIdByEmail("support-" + run + "@occpl.test"), PLATFORM_SUPPORT_ROLE);
    }

    @Test
    void registersLeadsWithValidationAndAuthorization() throws Exception {
        perform(post("/api/v1/leads"), "root", "{\"companyName\":\"\",\"contactName\":\"A\",\"email\":\"nope\"}")
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/leads"), "support", registerBody("Nope")).andExpect(status().isForbidden());
        perform(post("/api/v1/leads"), "plain", registerBody("Nope")).andExpect(status().isForbidden());

        JsonNode lead = register("Acme");
        assertThat(lead.get("status").asString()).isEqualTo("NEW");
        assertThat(lead.get("email").asString()).isEqualTo("buyer@acme-" + run + ".test");

        String id = lead.get("id").asString();
        perform(get("/api/v1/leads/" + id), "support", null).andExpect(status().isOk());
        perform(get("/api/v1/leads/" + id), "plain", null).andExpect(status().isForbidden());
        perform(get("/api/v1/leads/" + UUID.randomUUID()), "root", null).andExpect(status().isNotFound());

        perform(get("/api/v1/leads?q=Acme-" + run), "support", null).andExpect(jsonPath("$.totalItems").value(1));
        perform(get("/api/v1/leads?status=QUALIFIED&q=Acme-" + run), "support", null)
                .andExpect(jsonPath("$.totalItems").value(0));
        perform(get("/api/v1/leads?sort=notes"), "support", null).andExpect(status().isBadRequest());
    }

    @Test
    void updatesLeadWithOptimisticLocking() throws Exception {
        String id = register("Upd").get("id").asString();
        perform(patch("/api/v1/leads/" + id), "root", "{\"contactName\":\"Priya\",\"phone\":\"+91 98765 43210\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactName").value("Priya"))
                .andExpect(jsonPath("$.phone").value("+91 98765 43210"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(header().string("ETag", "\"1\""));
        perform(patch("/api/v1/leads/" + id).header("If-Match", "\"0\""), "root", "{\"notes\":\"stale\"}")
                .andExpect(status().isPreconditionFailed());
        perform(patch("/api/v1/leads/" + id), "root", "{\"phone\":\"\"}")
                .andExpect(jsonPath("$.phone").doesNotExist());
        perform(patch("/api/v1/leads/" + id), "root", "{\"email\":\"bad\"}").andExpect(status().isBadRequest());
        perform(patch("/api/v1/leads/" + id), "support", "{\"notes\":\"x\"}").andExpect(status().isForbidden());
    }

    @Test
    void movesThroughTheStatusFlow() throws Exception {
        String id = register("Flow").get("id").asString();
        String url = "/api/v1/leads/" + id + "/status";
        perform(post(url), "root", "{\"status\":\"CONVERTED\"}").andExpect(status().isUnprocessableContent());
        perform(post(url), "root", "{\"status\":\"CONTACTED\"}").andExpect(jsonPath("$.status").value("CONTACTED"));
        perform(post(url), "root", "{\"status\":\"QUALIFIED\"}").andExpect(jsonPath("$.status").value("QUALIFIED"));
        perform(post(url), "support", "{\"status\":\"CONVERTED\"}").andExpect(status().isForbidden());
        perform(post(url), "root", "{\"status\":\"CONVERTED\",\"reason\":\"signed\"}")
                .andExpect(jsonPath("$.status").value("CONVERTED"));
        perform(post(url), "root", "{\"status\":\"NEW\"}").andExpect(status().isUnprocessableContent());
        perform(post(url), "root", "{\"status\":\"bogus\"}").andExpect(status().isBadRequest());

        String lostId = register("Lost").get("id").asString();
        String lostUrl = "/api/v1/leads/" + lostId + "/status";
        perform(post(lostUrl), "root", "{\"status\":\"LOST\"}").andExpect(jsonPath("$.status").value("LOST"));
        perform(post(lostUrl), "root", "{\"status\":\"NEW\"}").andExpect(jsonPath("$.status").value("NEW"));
    }

    private String registerBody(String company) {
        return "{\"companyName\":\"" + company + "-" + run + "\",\"contactName\":\"Buyer\","
                + "\"email\":\"Buyer@" + company.toLowerCase() + "-" + run + ".test\",\"source\":\"Website\"}";
    }

    private JsonNode register(String company) throws Exception {
        return body(perform(post("/api/v1/leads"), "root", registerBody(company)).andExpect(status().isCreated()));
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
