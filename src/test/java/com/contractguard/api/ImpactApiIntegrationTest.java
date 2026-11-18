package com.contractguard.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.contractguard.specs.Fixtures;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level verification that consumer loading and impact analysis are wired into the
 * application flow: the registry is served and a version comparison returns the full
 * impact report a user (and the next milestone's policy gate) will consume. Deep
 * reason/level assertions live in the analyzer and kit tests; here we verify wiring
 * and payload shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImpactApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/consumers lists the four registered kit consumers with bindings")
    void consumersEndpoint() throws Exception {
        mockMvc.perform(get("/api/consumers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].consumer").value("mobile-app"))
                .andExpect(jsonPath("$[0].operations.length()").value(4))
                .andExpect(jsonPath("$[0].operations[1].path").value("/quotes/{id}"));
    }

    @Test
    @DisplayName("POST /api/impact for quote 1.1.0 → 2.0.0 returns findings + per-consumer impact")
    void impactEndpoint() throws Exception {
        String body = """
                {
                  "service": "quote-api",
                  "oldSpec": %s,
                  "newSpec": %s
                }
                """.formatted(json(Fixtures.spec("quote-api", "2-reorganised.yaml")),
                json(Fixtures.spec("quote-api", "3-price-lines.yaml")));

        mockMvc.perform(post("/api/impact").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("quote-api"))
                .andExpect(jsonPath("$.oldVersion").value("1.1.0"))
                .andExpect(jsonPath("$.newVersion").value("2.0.0"))
                .andExpect(jsonPath("$.findings.length()").value(5))
                .andExpect(jsonPath("$.summary.consumers.length()").value(4))
                .andExpect(jsonPath("$.summary.affectedCount").value(1))
                .andExpect(jsonPath("$.summary.possibleCount").value(1))
                .andExpect(jsonPath("$.summary.consumers[?(@.consumer == 'mobile-app')].level")
                        .value("AFFECTED"))
                .andExpect(jsonPath("$.summary.consumers[?(@.consumer == 'partner-portal')].level")
                        .value("NONE"))
                .andExpect(jsonPath("$.summary.consumers[?(@.consumer == 'mobile-app')].impacts.length()")
                        .value(4));
    }

    @Test
    @DisplayName("invalid spec YAML is rejected with 400 and an explanatory error")
    void invalidSpecIs400() throws Exception {
        String body = """
                {
                  "service": "quote-api",
                  "oldSpec": "openapi: 3.0.3\\ninfo: {title: T, version: 1.0.0}\\npaths: {}",
                  "newSpec": "openapi: 3.0.3\\ninfo: {title: T, version: 1.0.0}\\npaths:\\n  /x:\\n    get:\\n      responses:\\n        '200':\\n          content:\\n            application/json:\\n              schema: {$ref: '#/components/schemas/Nope'}"
                }
                """;
        mockMvc.perform(post("/api/impact").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("missing service or spec fields are rejected with 400 (bean validation)")
    void validationErrors() throws Exception {
        mockMvc.perform(post("/api/impact").contentType("application/json")
                        .content("{\"service\": \"quote-api\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a syntactically broken JSON body is rejected with 400 and the app error contract")
    void malformedJsonBody() throws Exception {
        mockMvc.perform(post("/api/impact").contentType("application/json")
                        .content("{ service: , broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    private static String json(String yaml) {
        // minimal JSON string escaping for fixture YAML (quotes/newlines/backslashes)
        return "\"" + yaml.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}
