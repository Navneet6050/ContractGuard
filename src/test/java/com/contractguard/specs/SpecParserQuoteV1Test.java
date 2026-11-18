package com.contractguard.specs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Parses the actual quote API v1.0.0 fixture (without reference to its filename —
 * the file is loaded by path and all assertions are content-derived).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpecParserQuoteV1Test {

    private SpecDocument doc;

    @BeforeAll
    void parseActualQuoteV1() {
        doc = new SpecParser().parse(Fixtures.spec("quote-api", "1-initial.yaml"));
    }

    @Test
    @DisplayName("quote v1.0.0: metadata is read from content")
    void metadata() {
        assertThat(doc.getOpenapiVersion()).isEqualTo("3.0.3");
        assertThat(doc.getTitle()).isEqualTo("Quote API");
        assertThat(doc.getSpecVersion()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("quote v1.0.0: effective map has one location per wire contract point")
    void effectiveLocations() {
        var keys = doc.getEffectiveSchemas().keySet();
        assertThat(keys).contains(
                "POST /quotes :: requestBody:application/json",
                "POST /quotes :: response:201:application/json",
                "POST /quotes :: response:400",
                "GET /quotes/{0} :: param:path:1",
                "GET /quotes/{0} :: response:200:application/json",
                "GET /quotes/{0} :: response:404",
                "DELETE /internal/pricing-cache :: response:204");
        assertThat(keys).hasSize(7);
    }

    @Test
    @DisplayName("quote v1.0.0: $refs are inline-expanded into the effective request schema")
    void requestBodyIsExpanded() {
        String body = doc.getEffectiveSchemas().get("POST /quotes :: requestBody:application/json");
        assertThat(body).contains("\"vehicle\"");
        assertThat(body).contains("\"cc\"");
        // expansion must have erased the component name
        assertThat(body).doesNotContain("Vehicle");
        assertThat(body).doesNotContain("$ref");
        // nested required lists survive, sorted (required is treated as a set)
        assertThat(body).contains("\"required\":[\"cc\",\"make\"]");
        // top-level required lists are sorted too
        assertThat(body).contains("\"required\":[\"rider\",\"vehicle\"]");
    }

    @Test
    @DisplayName("quote v1.0.0: response schema is the expanded Quote shape")
    void responseShape() {
        String response = doc.getEffectiveSchemas().get("POST /quotes :: response:201:application/json");
        assertThat(response).contains("\"quoteId\"");
        assertThat(response).contains("\"DRAFT\"");
        assertThat(response).contains("\"breakdown\"");
        assertThat(response).contains("\"amount\"");
        assertThat(response).doesNotContain("Premium\"");
        assertThat(response).doesNotContain("$ref");
    }

    @Test
    @DisplayName("quote v1.0.0: status-only responses are recorded as schema-less")
    void schemalessResponses() {
        assertThat(doc.getEffectiveSchemas().get("POST /quotes :: response:400")).isEqualTo("null");
        assertThat(doc.getEffectiveSchemas().get("DELETE /internal/pricing-cache :: response:204")).isEqualTo("null");
    }

    @Test
    @DisplayName("quote v1.0.0: x-internal operation is present with no parameters")
    void internalOperation() {
        var keys = doc.getEffectiveSchemas().keySet();
        assertThat(keys.stream().filter(k -> k.startsWith("DELETE /internal/pricing-cache"))).hasSize(1);
    }

    @Test
    @DisplayName("quote v1.0.0: no deprecated fields")
    void noDeprecations() {
        assertThat(doc.getDeprecations()).isEmpty();
    }
}
