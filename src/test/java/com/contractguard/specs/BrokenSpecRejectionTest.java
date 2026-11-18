package com.contractguard.specs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Policy rule 4: invalid specifications (including unresolved $refs) are rejected with
 * ALL collected errors. Uses the actual broken claims fixture plus filename-independent
 * synthetic documents.
 */
class BrokenSpecRejectionTest {

    private final SpecParser parser = new SpecParser();

    @Test
    @DisplayName("claims v1.1.0 (broken $ref) is rejected and names the missing component")
    void brokenClaimsRefIsRejected() {
        String broken = Fixtures.spec("claims-api", "2-channel-and-notes.yaml");

        assertThatThrownBy(() -> parser.parse(broken))
                .isInstanceOf(SpecParseException.class)
                .satisfies(e -> {
                    SpecParseException spe = (SpecParseException) e;
                    List<SpecValidationError> errors = spe.getErrors();
                    assertThat(errors).isNotEmpty();
                    assertThat(errors).anyMatch(err ->
                            err.message().contains("ClaimDocumnt")
                                    && err.message().contains("unresolved reference"));
                    // the pointer pinpoints the offending node
                    assertThat(errors).anyMatch(err ->
                            err.pointer().contains("/components/schemas/Claim/properties/documents"));
                });
    }

    @Test
    @DisplayName("corrected claims v1.1.0 is accepted")
    void correctedClaimsIsAccepted() {
        SpecDocument doc = parser.parse(Fixtures.spec("claims-api", "3-channel-and-notes-fixed.yaml"));
        assertThat(doc.getTitle()).isEqualTo("Claims API");
        assertThat(doc.getSpecVersion()).isEqualTo("1.1.0");
        assertThat(doc.getDeprecations()).isEmpty();
        // documents the new optional fields of 1.1.0
        String body = doc.getEffectiveSchemas().get("POST /claims :: requestBody:application/json");
        assertThat(body).contains("channel");
        String claim = doc.getEffectiveSchemas().get("GET /claims/{0} :: response:200:application/json");
        assertThat(claim).contains("assessorNotes");
        assertThat(claim).contains("ClaimDocument".substring(0, 0) + "docId"); // content, not name
        assertThat(claim).doesNotContain("ClaimDocumnt\"");
    }

    @Test
    @DisplayName("synthetic: unresolved ref is rejected with a precise pointer")
    void syntheticUnresolvedRef() {
        String yaml = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Missing'
                components:
                  schemas:
                    Other: {type: string}
                """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(SpecParseException.class)
                .satisfies(e -> {
                    List<SpecValidationError> errors = ((SpecParseException) e).getErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).message()).contains("Missing");
                    assertThat(errors.get(0).pointer())
                            .isEqualTo("/paths/~1a/get/responses/200/content/application~1json/schema/$ref");
                });
    }

    @Test
    @DisplayName("synthetic: ALL errors are collected, not just the first")
    void collectsAllErrors() {
        String yaml = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/M1'
                  /b:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              $ref: '#/components/schemas/M2'
                      responses: {'200': {description: ok}}
                components:
                  schemas: {}
                """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(SpecParseException.class)
                .satisfies(e -> {
                    List<SpecValidationError> errors = ((SpecParseException) e).getErrors();
                    assertThat(errors).hasSize(2);
                    assertThat(errors).anyMatch(err -> err.message().contains("M1"));
                    assertThat(errors).anyMatch(err -> err.message().contains("M2"));
                });
    }

    @Test
    @DisplayName("synthetic: non-local refs are rejected")
    void nonLocalRefRejected() {
        String yaml = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: 'https://other.host/schemas/Thing.yaml'
                """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(SpecParseException.class)
                .satisfies(e -> {
                    List<SpecValidationError> errors = ((SpecParseException) e).getErrors();
                    assertThat(errors).anyMatch(err -> err.message().contains("non-local reference"));
                });
    }

    @Test
    @DisplayName("synthetic: skeleton violations (missing info.version) are rejected")
    void missingVersionRejected() {
        String yaml = """
                openapi: 3.0.3
                info: {title: T}
                paths:
                  /a: {get: {responses: {'200': {description: ok}}}}
                """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(SpecParseException.class)
                .satisfies(e -> {
                    List<SpecValidationError> errors = ((SpecParseException) e).getErrors();
                    assertThat(errors).anyMatch(err -> err.pointer().equals("/info/version"));
                });
    }

    @Test
    @DisplayName("synthetic: malformed YAML is rejected cleanly")
    void malformedYamlRejected() {
        assertThatThrownBy(() -> parser.parse("{{{{ not yaml"))
                .isInstanceOf(SpecParseException.class)
                .satisfies(e -> assertThat(((SpecParseException) e).getErrors()).isNotEmpty());

        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(SpecParseException.class)
                .satisfies(e -> assertThat(((SpecParseException) e).getErrors()).anyMatch(
                        err -> err.message().contains("empty")));
    }
}
