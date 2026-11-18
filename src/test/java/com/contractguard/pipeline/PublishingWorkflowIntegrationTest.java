package com.contractguard.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.contractguard.specs.Fixtures;

/**
 * End-to-end publishing workflow over the in-memory H2 (test config): registration,
 * gated uploads, dry-run check, duplicate/regression/invalid-spec rejection, the
 * acknowledge/override/abandon state machine, persistence of versions/findings/audit
 * and the non-fatal webhook log. The gate RULES themselves are unit-tested in
 * PublishingDecisionTest; here the rules are exercised through the HTTP surface.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PublishingWorkflowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ---------------------------------------------------------------- fixtures

    /** First version of a toy service no consumer file references. */
    private static final String WIDGET_V1 = """
            openapi: 3.0.3
            info: {title: Widgets, version: '1.0.0'}
            paths:
              /widgets:
                get:
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            required: [id, name]
                            properties:
                              id: {type: string}
                              name: {type: string}
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [name]
                          properties:
                            name: {type: string}
                  responses:
                    '201': {description: created}
            """;

    /** Additive change: one more optional response property. Rule 1: auto-publish. */
    private static final String WIDGET_V2 = """
            openapi: 3.0.3
            info: {title: Widgets, version: '1.1.0'}
            paths:
              /widgets:
                get:
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            required: [id, name]
                            properties:
                              id: {type: string}
                              name: {type: string}
                              note: {type: string}
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [name]
                          properties:
                            name: {type: string}
                  responses:
                    '201': {description: created}
            """;

    /** Same version as V2 but different content -> hard conflict (nothing stored). */
    private static final String WIDGET_V2_MUTATED = WIDGET_V2.replace("note: {type: string}",
            "note: {type: string, maxLength: 3}");

    /** Breaking change: required property added on the REQUEST side, no consumers -> pending_ack. */
    private static final String WIDGET_V3 = """
            openapi: 3.0.3
            info: {title: Widgets, version: '2.0.0'}
            paths:
              /widgets:
                get:
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            required: [id, name]
                            properties:
                              id: {type: string}
                              name: {type: string}
                              note: {type: string}
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [name, region]
                          properties:
                            name: {type: string}
                            region: {type: string}
                  responses:
                    '201': {description: created}
            """;

    /** Version regression vs the published 2.0.0. */
    private static final String WIDGET_V19 = WIDGET_V2.replace("'1.1.0'", "'1.9.0'");

    /** Invalid: unresolved local $ref. Rejection is unconditional (policy rule 4). */
    private static final String WIDGET_BROKEN = """
            openapi: 3.0.3
            info: {title: Widgets, version: '3.0.0'}
            paths:
              /widgets:
                get:
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Nope'
            """;

    /** Another breaking request change (for the abandon path). */
    private static final String WIDGET_V4 = """
            openapi: 3.0.3
            info: {title: Widgets, version: '2.1.0'}
            paths:
              /widgets:
                get:
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            required: [id, name]
                            properties:
                              id: {type: string}
                              name: {type: string}
                              note: {type: string}
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [name, region, zone]
                          properties:
                            name: {type: string}
                            region: {type: string}
                            zone: {type: string}
                  responses:
                    '201': {description: created}
            """;

    // ---------------------------------------------------------------- helpers

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    private static String uploadBody(String yaml, String filename) {
        return "{\"yaml\": %s, \"filename\": %s}".formatted(json(yaml), json(filename));
    }

    private static String registerBody(String slug, String title) {
        return """
                {"slug": %s, "title": %s, "ownerTeam": "test", "description": "workflow test"}"""
                .formatted(json(slug), json(title));
    }

    private static long parseAttemptId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        var matcher = Pattern.compile("\"attemptId\":(\\d+)").matcher(body);
        assertThat(matcher.find()).as("attemptId present in " + body).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    // ---------------------------------------------------------------- the workflow

    @Test
    @Order(1)
    @DisplayName("services are registered and appear in the registry")
    void registerServices() throws Exception {
        mockMvc.perform(post("/api/services").contentType("application/json")
                        .content(registerBody("widget-api", "Widgets API")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("widget-api"));
        mockMvc.perform(post("/api/services").contentType("application/json")
                        .content(registerBody("quote-api", "Quotes API")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("quote-api"));
        mockMvc.perform(post("/api/services").contentType("application/json")
                        .content(registerBody("widget-api", "dup")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("already registered")));
        mockMvc.perform(post("/api/services").contentType("application/json")
                        .content(registerBody("Bad_Slug", "bad")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(2)
    @DisplayName("rule 1: additive versions auto-publish and become the current version")
    void autoPublishFlow() throws Exception {
        mockMvc.perform(post("/api/services/widget-api/specs").contentType("application/json")
                        .header("X-Acting-User", "alice")
                        .content(uploadBody(WIDGET_V1, "widget-1.0.0.yaml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("auto_published"))
                .andExpect(jsonPath("$.decision").value("PUBLISH"))
                .andExpect(jsonPath("$.findings.length()").value(0));

        mockMvc.perform(post("/api/services/widget-api/specs").contentType("application/json")
                        .content(uploadBody(WIDGET_V2, "widget-1.1.0.yaml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("auto_published"));

        mockMvc.perform(get("/api/services/widget-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value("1.1.0"))
                .andExpect(jsonPath("$.versions", hasSize(2)))
                .andExpect(jsonPath("$.versions[0].version").value("1.1.0"))
                .andExpect(jsonPath("$.versions[0].state").value("published"))
                .andExpect(jsonPath("$.versions[1].state").value("superseded"));

        mockMvc.perform(get("/api/services/widget-api/versions/1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedBy").value("alice"))
                .andExpect(jsonPath("$.spec", containsString("version: '1.0.0'")));
    }

    @Test
    @Order(3)
    @DisplayName("identical re-upload is an idempotent no-op; differing content under the same version is a 409")
    void duplicateSemantics() throws Exception {
        mockMvc.perform(post("/api/services/widget-api/specs").contentType("application/json")
                        .content(uploadBody(WIDGET_V1, "widget-1.0.0.yaml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate-identical"));

        mockMvc.perform(post("/api/services/widget-api/specs").contentType("application/json")
                        .content(uploadBody(WIDGET_V2_MUTATED, "mutated.yaml")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("different content")));

        mockMvc.perform(get("/api/services/widget-api/versions"))
                .andExpect(jsonPath("$.length()").value(2)); // the rejection reserved nothing
    }

    @Test
    @Order(4)
    @DisplayName("dry-run check returns the full decision report and publishes nothing")
    void dryRunCheck() throws Exception {
        mockMvc.perform(post("/api/services/widget-api/check").contentType("application/json")
                        .header("X-Acting-User", "diana")
                        .content(uploadBody(WIDGET_V3, "widget-2.0.0.yaml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PENDING_ACK"))
                .andExpect(jsonPath("$.requiredAction", containsString("acknowledge")))
                .andExpect(jsonPath("$.reasons").isArray())
                .andExpect(jsonPath("$.note", containsString("dry-run")));

        mockMvc.perform(get("/api/services/widget-api/versions"))
                .andExpect(jsonPath("$.length()").value(2)); // the check registered nothing
    }

    @Test
    @Order(5)
    @DisplayName("invalid specs are rejected 422 with every error listed; a rejected attempt is audited, nothing stored")
    void invalidSpecRejection() throws Exception {
        mockMvc.perform(post("/api/services/widget-api/specs").contentType("application/json")
                        .header("X-Acting-User", "bob")
                        .content(uploadBody(WIDGET_BROKEN, "broken.yaml")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("invalid specification")))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.attemptId").exists());

        mockMvc.perform(get("/api/services/widget-api/versions"))
                .andExpect(jsonPath("$.length()").value(2)); // nothing stored for the broken upload

        mockMvc.perform(get("/api/audit").param("service", "widget-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", hasItem("SPEC_REJECTED")));
    }

    @Test
    @Order(6)
    @DisplayName("rule 2: breaking change with no affected consumer -> pending_ack, then acknowledge publishes")
    void acknowledgeFlow() throws Exception {
        MvcResult upload = mockMvc.perform(post("/api/services/widget-api/specs")
                        .contentType("application/json")
                        .header("X-Acting-User", "carol")
                        .content(uploadBody(WIDGET_V3, "widget-2.0.0.yaml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("pending_ack"))
                .andExpect(jsonPath("$.decision").value("PENDING_ACK"))
                .andExpect(jsonPath("$.breakingCount").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.allowedActions", hasItem("acknowledge")))
                .andReturn();
        long attemptId = parseAttemptId(upload);

        // the pending attempt stored no version
        mockMvc.perform(get("/api/services/widget-api/versions"))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/attempts/" + attemptId))
                .andExpect(jsonPath("$.state").value("pending_ack"))
                .andExpect(jsonPath("$.findings[*].severity", hasItem("breaking")));

        mockMvc.perform(post("/api/attempts/" + attemptId + "/acknowledge")
                        .contentType("application/json")
                        .header("X-Acting-User", "dave")
                        .content("{\"comment\": \"reviewed, no consumer affected\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("published"));

        mockMvc.perform(get("/api/services/widget-api"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // the same attempt cannot be acknowledged or overridden again (wrong state)
        mockMvc.perform(post("/api/attempts/" + attemptId + "/acknowledge")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("pending_ack")));
        mockMvc.perform(post("/api/attempts/" + attemptId + "/override")
                        .contentType("application/json")
                        .content("{\"justification\": \"late attempt\"}"))
                .andExpect(status().isBadRequest());

        // version regression vs the now-current 2.0.0
        mockMvc.perform(post("/api/services/widget-api/specs").contentType("application/json")
                        .content(uploadBody(WIDGET_V19, "widget-1.9.0.yaml")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("regression")));
    }

    @Test
    @Order(7)
    @DisplayName("rule 3: breaking change with an AFFECTED consumer -> blocked; override requires justification")
    void overrideFlow() throws Exception {
        mockMvc.perform(post("/api/services/quote-api/specs").contentType("application/json")
                        .content(uploadBody(Fixtures.spec("quote-api", "1-initial.yaml"),
                                "1-initial.yaml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("auto_published"));

        mockMvc.perform(post("/api/services/quote-api/specs").contentType("application/json")
                        .content(uploadBody(Fixtures.spec("quote-api", "2-reorganised.yaml"),
                                "2-reorganised.yaml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("auto_published"));

        MvcResult blocked = mockMvc.perform(post("/api/services/quote-api/specs")
                        .contentType("application/json")
                        .header("X-Acting-User", "erin")
                        .content(uploadBody(Fixtures.spec("quote-api", "3-price-lines.yaml"),
                                "3-price-lines.yaml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("blocked"))
                .andExpect(jsonPath("$.decision").value("BLOCK"))
                .andExpect(jsonPath("$.affectedConsumers", hasItem("mobile-app")))
                .andReturn();
        long attemptId = parseAttemptId(blocked);

        // blank or missing justification does not unlock a blocked publication (422)
        mockMvc.perform(post("/api/attempts/" + attemptId + "/override")
                        .contentType("application/json")
                        .content("{\"justification\": \"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("justification")));
        mockMvc.perform(post("/api/attempts/" + attemptId + "/override")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/attempts/" + attemptId + "/override")
                        .contentType("application/json")
                        .header("X-Acting-User", "frank")
                        .content("{\"justification\": \"mobile-app team confirmed in writing; rename is coordinated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("overridden_published"));

        mockMvc.perform(get("/api/services/quote-api"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // overriding the already-decided attempt again is a wrong-state 400
        mockMvc.perform(post("/api/attempts/" + attemptId + "/override")
                        .contentType("application/json")
                        .content("{\"justification\": \"again\"}"))
                .andExpect(status().isBadRequest());

        // cumulative consumer-drift scan: with 2.0.0 published (via the override), the
        // later 2.1.0 upload still warns that mobile-app's declaration references a field
        // that no longer exists — advisory only, so the upload still auto-publishes
        MvcResult next = mockMvc.perform(post("/api/services/quote-api/specs")
                        .contentType("application/json")
                        .content(uploadBody(Fixtures.spec("quote-api", "4-ncb-and-taxes.yaml"),
                                "4-ncb-and-taxes.yaml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("auto_published"))
                .andExpect(jsonPath("$.decision").value("PUBLISH"))
                .andReturn();
        String driftBody = next.getResponse().getContentAsString();
        assertThat(driftBody).contains("CONSUMER.DRIFT").contains("premium.breakdown[].amount")
                .contains("mobile-app");

        // migration guide for the blocked attempt: grounded in the stored findings
        MvcResult guide = mockMvc.perform(get("/api/attempts/" + attemptId + "/migration-guide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.steps[*].code", hasItem("RESP.PROPERTY_RENAMED")))
                .andReturn();
        String guideBody = guide.getResponse().getContentAsString();
        assertThat(guideBody).contains("premium.breakdown[].amount")
                .contains("mobile-app (AFFECTED)").contains("Update readers");
    }

    @Test
    @Order(8)
    @DisplayName("history and changelog reflect every published transition; semver warnings stay a separate channel")
    void historyAndChangelog() throws Exception {
        mockMvc.perform(get("/api/services/widget-api/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.versions[*].version", hasItem("2.0.0")))
                .andExpect(jsonPath("$.attempts[*].state", hasItem("auto_published")))
                .andExpect(jsonPath("$.attempts[*].state", hasItem("published")))
                .andExpect(jsonPath("$.attempts[*].state", hasItem("rejected")));

        MvcResult log = mockMvc.perform(get("/api/services/widget-api/changelog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andReturn();
        String body = log.getResponse().getContentAsString();
        assertThat(body).contains("\"version\":\"1.0.0\"").contains("\"version\":\"1.1.0\"")
                .contains("\"version\":\"2.0.0\"").contains("\"policyWarnings\":");

        MvcResult quoteLog = mockMvc.perform(get("/api/services/quote-api/changelog"))
                .andExpect(status().isOk())
                .andReturn();
        String quoteBody = quoteLog.getResponse().getContentAsString();
        assertThat(quoteBody).contains("mobile-app team confirmed in writing")
                .contains("overridden_published");
    }

    @Test
    @Order(9)
    @DisplayName("audit records registration, uploads, rejections, check and decisions with actors")
    void auditTrail() throws Exception {
        MvcResult audit = mockMvc.perform(get("/api/audit").param("service", "widget-api"))
                .andExpect(status().isOk())
                .andReturn();
        String body = audit.getResponse().getContentAsString();
        for (String action : List.of("SERVICE_REGISTERED", "SPEC_UPLOADED", "SPEC_REJECTED",
                "COMPATIBILITY_CHECK", "ACKNOWLEDGED")) {
            assertThat(body).contains("\"action\":\"" + action + "\"");
        }
        assertThat(body).contains("\"actor\":\"alice\"").contains("\"actor\":\"dave\"")
                .contains("\"actor\":\"bob\"");

        MvcResult quoteAudit = mockMvc.perform(get("/api/audit").param("service", "quote-api"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(quoteAudit.getResponse().getContentAsString())
                .contains("\"action\":\"OVERRIDE\"")
                .contains("mobile-app team confirmed in writing");
    }

    @Test
    @Order(10)
    @DisplayName("webhook delivery is attempted on publish and its outcome logged, never fatal")
    void webhookLog() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(11)
    @DisplayName("abandon withdraws a pending attempt without storing the version")
    void abandonFlow() throws Exception {
        MvcResult pending = mockMvc.perform(post("/api/services/widget-api/specs")
                        .contentType("application/json")
                        .content(uploadBody(WIDGET_V4, "widget-2.1.0.yaml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("pending_ack"))
                .andReturn();
        long attemptId = parseAttemptId(pending);
        mockMvc.perform(post("/api/attempts/" + attemptId + "/abandon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("abandoned"));
        mockMvc.perform(get("/api/services/widget-api/versions"))
                .andExpect(jsonPath("$.length()").value(3)) // 1.0.0, 1.1.0, 2.0.0 — no 2.1.0
                .andExpect(jsonPath("$[*].version", hasItem("2.0.0")));
    }

    @Test
    @Order(12)
    @DisplayName("metrics aggregate the whole workflow from real rows")
    void metrics() throws Exception {
        MvcResult out = mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services").value(2))
                .andExpect(jsonPath("$.breakingFindingsTotal", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.affectedConsumerImpacts", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.blockedPublications", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.overrides", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.rejections", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.webhookNotificationsTotal", greaterThanOrEqualTo(1)))
                .andReturn();
        String body = out.getResponse().getContentAsString();
        assertThat(body).contains("\"mobile-app\"").contains("overridden_published");
    }
}
