package com.contractguard.compatibility;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.contractguard.compatibility.CompatibilityFinding;
import com.contractguard.compatibility.CompatibilityEngine;
import com.contractguard.compatibility.Severity;
import com.contractguard.specs.SpecDocument;
import com.contractguard.specs.SpecParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule-by-rule tests over synthetic spec pairs. Pairs are parsed with the real
 * SpecParser so the engine always runs against genuine effective-schema maps (the
 * engine's own contract), never against hand-rolled YAML trees.
 */
class SyntheticCompatibilityTest {

    private final SpecParser parser = new SpecParser();
    private final CompatibilityEngine engine = new CompatibilityEngine();

    private List<CompatibilityFinding> diff(String oldYaml, String newYaml) {
        SpecDocument oldDoc = parser.parse(oldYaml);
        SpecDocument newDoc = parser.parse(newYaml);
        return engine.diff(oldDoc.getEffectiveSchemas(), newDoc.getEffectiveSchemas(),
                oldDoc.getPathAliases(), newDoc.getPathAliases());
    }

    private static final String HEADER = """
            openapi: 3.0.3
            info: {title: T, version: 1.0.0}
            paths:
            """;

    @Test
    @DisplayName("REQUEST: required added is breaking; required removed is info")
    void requestRequiredSemantics() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, required: [x], properties: {x: {type: string}}}
                      responses: {'200': {description: ok}}
                """;
        String withRequired = oldY.replace("required: [x]", "required: [x, y]")
                .replace("properties: {x: {type: string}}", "properties: {x: {type: string}, y: {type: string}}");
        List<CompatibilityFinding> added = diff(oldY, withRequired);
        assertThat(added).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("REQ.REQUIRED_ADDED");
            assertThat(f.severity()).isEqualTo(Severity.BREAKING);
            assertThat(f.fieldPath()).isEqualTo("y");
        });

        String withoutRequired = oldY.replace("required: [x], ", "");
        List<CompatibilityFinding> removed = diff(oldY, withoutRequired);
        assertThat(removed).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("REQ.REQUIRED_REMOVED");
            assertThat(f.severity()).isEqualTo(Severity.INFO);
        });
        assertThat(removed).noneSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
    }

    @Test
    @DisplayName("RESPONSE: required removed is breaking; required added is info")
    void responseRequiredSemantics() {
        String oldY = HEADER + """
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, required: [id, total], properties: {id: {type: string}, total: {type: number}}}
                """;
        String noLongerGuaranteed = oldY.replace("required: [id, total]", "required: [id]");
        List<CompatibilityFinding> removed = diff(oldY, noLongerGuaranteed);
        assertThat(removed).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("RESP.REQUIRED_REMOVED");
            assertThat(f.severity()).isEqualTo(Severity.BREAKING);
            assertThat(f.fieldPath()).isEqualTo("total");
        });

        String strengthened = oldY.replace("required: [id, total]", "required: [id, total, extra]")
                .replace("total: {type: number}}", "total: {type: number}, extra: {type: string}}");
        List<CompatibilityFinding> added = diff(oldY, strengthened);
        assertThat(added).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("RESP.REQUIRED_ADDED");
            assertThat(f.severity()).isEqualTo(Severity.INFO);
        });
        assertThat(added).noneSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
    }

    @Test
    @DisplayName("ENUM: narrowing breaks senders (request); widening breaks strict readers (response)")
    void enumSemantics() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {mode: {type: string, enum: [A, B, C]}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {state: {type: string, enum: [X, Y]}}}
                """;
        String newY = oldY.replace("enum: [A, B, C]", "enum: [A, B]")
                .replace("enum: [X, Y]", "enum: [X, Y, Z]");

        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.ENUM_NARROWED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.detail()).contains("C");
                    assertThat(f.fieldPath()).isEqualTo("mode");
                });
        assertThat(findings).filteredOn(f -> f.code().equals("RESP.ENUM_WIDENED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.detail()).contains("Z");
                    assertThat(f.fieldPath()).isEqualTo("state");
                });
    }

    @Test
    @DisplayName("ENUM: narrowing on response and widening on request are informational")
    void enumBenignDirections() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {mode: {type: string, enum: [A, B]}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {state: {type: string, enum: [X, Y, Z]}}}
                """;
        String newY = oldY.replace("enum: [A, B]", "enum: [A, B, C]")
                .replace("enum: [X, Y, Z]", "enum: [X, Y]");
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.ENUM_WIDENED"))
                .hasSize(1).allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        assertThat(findings).filteredOn(f -> f.code().equals("RESP.ENUM_NARROWED"))
                .hasSize(1).allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        assertThat(findings).noneSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
    }

    @Test
    @DisplayName("TYPE: number → integer narrows the request (breaking); unconstrained → string narrows the response (info)")
    void typeSemantics() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {amount: {type: number}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {code: {}}}
                """;
        String newY = oldY.replace("amount: {type: number}", "amount: {type: integer}")
                .replace("code: {}", "code: {type: string}");
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.TYPE_NARROWED"))
                .hasSize(1).allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
        assertThat(findings).filteredOn(f -> f.code().equals("RESP.TYPE_NARROWED"))
                .hasSize(1).allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        // narrowing breaks the request side; the response side must stay non-breaking
        assertThat(findings).filteredOn(f -> f.scope().equals("RESPONSE"))
                .noneSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
    }

    @Test
    @DisplayName("TYPE: fully disjoint change is breaking on both sides; response keeps its own code")
    void disjointTypeChange() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {tag: {type: string}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {tag: {type: string}}}
                """;
        String newY = oldY.replace("tag: {type: string}", "tag: {type: integer}");
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.TYPE_NARROWED"))
                .hasSize(1).allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
        assertThat(findings).filteredOn(f -> f.code().equals("RESP.TYPE_CHANGED"))
                .hasSize(1).allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
    }

    @Test
    @DisplayName("NULLABLE: nullable added on response is breaking; removed on response is info")
    void nullableSemantics() {
        String oldY = HEADER + """
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {note: {type: string}}}
                """;
        String nullable = oldY.replace("note: {type: string}", "note: {type: string, nullable: true}");
        List<CompatibilityFinding> added = diff(oldY, nullable);
        assertThat(added).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("RESP.NULLABLE_ADDED");
            assertThat(f.severity()).isEqualTo(Severity.BREAKING);
        });
        List<CompatibilityFinding> removed = diff(nullable, oldY);
        assertThat(removed).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("RESP.CONSTRAINT_TIGHTENED");
            assertThat(f.severity()).isEqualTo(Severity.INFO);
        });
    }

    @Test
    @DisplayName("CONSTRAINTS: maximum added on request is breaking; maximum removed on response is breaking; tightened on response is info")
    void constraintSemantics() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {age: {type: integer}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {score: {type: integer, maximum: 100}}}
                """;
        String newY = oldY.replace("age: {type: integer}", "age: {type: integer, maximum: 50}")
                .replace("maximum: 100", "maximum: 50");
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.CONSTRAINT_TIGHTENED"))
                .hasSize(1).allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.detail()).contains("maximum").contains("50");
                });
        assertThat(findings).filteredOn(f -> f.code().equals("RESP.CONSTRAINT_TIGHTENED"))
                .hasSize(1).allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));

        String loosened = oldY.replace(", maximum: 100", "");
        List<CompatibilityFinding> loosenedFindings = diff(oldY, loosened);
        assertThat(loosenedFindings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("RESP.CONSTRAINT_LOOSENED");
            assertThat(f.severity()).isEqualTo(Severity.BREAKING);
        });
    }

    @Test
    @DisplayName("RENAME: same-shape pair becomes one PROPERTY_RENAMED; different shapes stay REMOVED+ADDED")
    void renameDetection() {
        String oldY = HEADER + """
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {amount: {type: number}, keep: {type: string}}}
                """;
        String renamedY = oldY.replace("amount: {type: number}", "value: {type: number}");
        List<CompatibilityFinding> renamed = diff(oldY, renamedY);
        assertThat(renamed).filteredOn(f -> f.code().equals("RESP.PROPERTY_RENAMED"))
                .hasSize(1).allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.fieldPath()).isEqualTo("amount");
                    assertThat(f.detail()).contains("'amount'").contains("'value'");
                });
        assertThat(renamed).filteredOn(f -> f.code().equals("RESP.PROPERTY_REMOVED")).isEmpty();
        assertThat(renamed).filteredOn(f -> f.code().equals("RESP.PROPERTY_ADDED")).isEmpty();

        String changedTypeY = oldY.replace("amount: {type: number}", "value: {type: string}");
        List<CompatibilityFinding> notARename = diff(oldY, changedTypeY);
        assertThat(notARename).filteredOn(f -> f.code().equals("RESP.PROPERTY_REMOVED")).hasSize(1);
        assertThat(notARename).filteredOn(f -> f.code().equals("RESP.PROPERTY_ADDED")).hasSize(1);
        assertThat(notARename).filteredOn(f -> f.code().equals("RESP.PROPERTY_RENAMED")).isEmpty();
    }

    @Test
    @DisplayName("OPERATIONS: removed operation is breaking; added operation is info")
    void operationLifecycle() {
        String oldY = HEADER + """
                  /a:
                    get:
                      responses: {'200': {description: ok}}
                  /b:
                    get:
                      responses: {'200': {description: ok}}
                """;
        String newY = oldY.replace("""
                  /a:
                    get:
                      responses: {'200': {description: ok}}
                """, "") + """
                  /c:
                    get:
                      responses: {'200': {description: ok}}
                """;
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).filteredOn(f -> f.code().equals("OPERATION.REMOVED"))
                .hasSize(1).allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.method()).isEqualTo("GET");
                    assertThat(f.path()).isEqualTo("/a");
                });
        assertThat(findings).filteredOn(f -> f.code().equals("OPERATION.ADDED"))
                .hasSize(1).allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
    }

    @Test
    @DisplayName("RESPONSES: removed status is breaking; new documented status is info; schema-less gaining a body is info")
    void responseLifecycle() {
        String oldY = HEADER + """
                  /a:
                    get:
                      responses:
                        '200': {description: ok}
                        '404': {description: missing}
                """;
        String newY = oldY.replace("        '404': {description: missing}\n", "");
        List<CompatibilityFinding> removed = diff(oldY, newY);
        assertThat(removed).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("RESP.RESPONSE_REMOVED");
            assertThat(f.severity()).isEqualTo(Severity.BREAKING);
        });

        String withNewStatus = oldY + "        '202': {description: accepted}\n";
        List<CompatibilityFinding> added = diff(oldY, withNewStatus);
        assertThat(added).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("RESP.RESPONSE_ADDED");
            assertThat(f.severity()).isEqualTo(Severity.INFO);
        });
        assertThat(added).noneSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));

        String gainsBody = oldY.replace("'200': {description: ok}",
                "'200': {description: ok, content: {application/json: {schema: {type: object}}}}");
        List<CompatibilityFinding> schemaAdded = diff(oldY, gainsBody);
        assertThat(schemaAdded).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("RESP.RESPONSE_SCHEMA_ADDED");
            assertThat(f.severity()).isEqualTo(Severity.INFO);
        });
        assertThat(schemaAdded).noneSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
    }

    @Test
    @DisplayName("PARAMETERS: removed param is breaking; new optional param is info; optional → required is breaking")
    void parameterSemantics() {
        String oldY = HEADER + """
                  /a:
                    get:
                      parameters:
                      - {name: limit, in: query, required: false, schema: {type: integer}}
                      responses: {'200': {description: ok}}
                """;
        List<CompatibilityFinding> removed = diff(oldY, oldY.replace(
                "      parameters:\n      - {name: limit, in: query, required: false, schema: {type: integer}}\n", ""));
        assertThat(removed).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("PARAM.REMOVED");
            assertThat(f.severity()).isEqualTo(Severity.BREAKING);
        });

        // region is APPENDED after limit: parameters are matched positionally, so the
        // new optional param occupies param:query:2 while limit stays at param:query:1
        String addedOptional = oldY.replace(
                "- {name: limit, in: query, required: false, schema: {type: integer}}",
                "- {name: limit, in: query, required: false, schema: {type: integer}}\n"
                        + "      - {name: region, in: query, required: false, schema: {type: string}}");
        List<CompatibilityFinding> optionalAdded = diff(oldY, addedOptional);
        assertThat(optionalAdded).filteredOn(f -> f.code().equals("PARAM.ADDED_OPTIONAL"))
                .hasSize(1).allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.INFO);
                    assertThat(f.location()).contains("param:query:2");
                });
        assertThat(optionalAdded).noneSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));

        String nowRequired = oldY.replace(
                "- {name: limit, in: query, required: false, schema: {type: integer}}",
                "- {name: limit, in: query, required: true, schema: {type: integer}}");
        List<CompatibilityFinding> requiredNow = diff(oldY, nowRequired);
        assertThat(requiredNow).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("PARAM.REQUIRED_ADDED");
            assertThat(f.severity()).isEqualTo(Severity.BREAKING);
        });
    }

    @Test
    @DisplayName("REQUEST BODY: removed media type is breaking")
    void requestBodyMediaRemoved() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json: {schema: {type: object}}
                      responses: {'200': {description: ok}}
                """;
        String newY = HEADER + """
                  /a:
                    post:
                      responses: {'200': {description: ok}}
                """;
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("REQUEST_BODY.REMOVED");
            assertThat(f.severity()).isEqualTo(Severity.BREAKING);
            assertThat(f.location()).contains("requestBody:application/json");
        });
    }

    @Test
    @DisplayName("DEFAULT: changed default on request is informational")
    void defaultChanged() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {limit: {type: integer, default: 10}}}
                      responses: {'200': {description: ok}}
                """;
        String newY = oldY.replace("default: 10", "default: 20");
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("REQ.DEFAULT_CHANGED");
        assertThat(findings.get(0).severity()).isEqualTo(Severity.INFO);
    }

    // ------------------------------------------------------- schema added/removed asymmetry

    @Test
    @DisplayName("SCHEMA_ADDED/REMOVED follow the asymmetry: a documented request schema narrows (breaking), a documented response schema strengthens (info)")
    void schemaAddRemoveAsymmetry() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json: {}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json: {}
                """;
        String newY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, required: [x], properties: {x: {type: string}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, required: [y], properties: {y: {type: string}}}
                """;
        List<CompatibilityFinding> added = diff(oldY, newY);
        assertThat(added).filteredOn(f -> f.code().equals("REQ.SCHEMA_ADDED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
        assertThat(added).filteredOn(f -> f.code().equals("RESP.SCHEMA_ADDED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));

        List<CompatibilityFinding> removed = diff(newY, oldY);
        assertThat(removed).filteredOn(f -> f.code().equals("REQ.SCHEMA_REMOVED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        assertThat(removed).filteredOn(f -> f.code().equals("RESP.SCHEMA_REMOVED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
    }

    // ------------------------------------------------------- constraint directions

    @Test
    @DisplayName("pattern changes report an explicit unknown direction; only provable subsets get a direction")
    void patternDirection() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {code: {type: string, pattern: '^A[0-9]{3}$'}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {code: {type: string, pattern: '^B[0-9]{3}$'}}}
                """;
        // two unrelated patterns: direction is unknowable — never mislabeled as tightening
        String newY = oldY.replace("^A[0-9]{3}$", "^A[0-9]{4}$").replace("^B[0-9]{3}$", "^B[0-9]{4}$");
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.CONSTRAINT_CHANGED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.INFO);
                    assertThat(f.detail()).contains("pattern").contains("direction cannot be determined");
                });
        assertThat(findings).filteredOn(f -> f.code().equals("RESP.CONSTRAINT_CHANGED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        assertThat(findings).noneSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));

        // .* accepts everything: any concrete pattern is provably tighter
        String allAccepted = oldY.replace("^A[0-9]{3}$", ".*").replace("^B[0-9]{3}$", ".*");
        List<CompatibilityFinding> tightened = diff(allAccepted, oldY);
        assertThat(tightened).filteredOn(f -> f.code().equals("REQ.CONSTRAINT_TIGHTENED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
        assertThat(tightened).filteredOn(f -> f.code().equals("RESP.CONSTRAINT_TIGHTENED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        List<CompatibilityFinding> loosened = diff(oldY, allAccepted);
        assertThat(loosened).filteredOn(f -> f.code().equals("RESP.CONSTRAINT_LOOSENED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));

        // a concrete pattern added over an unconstrained string is a real tightening
        String unconstrained = oldY.replace(", pattern: '^A[0-9]{3}$'", "")
                .replace(", pattern: '^B[0-9]{3}$'", "");
        List<CompatibilityFinding> addedConcrete = diff(unconstrained, oldY);
        assertThat(addedConcrete).filteredOn(f -> f.code().equals("REQ.CONSTRAINT_TIGHTENED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));

        // an all-accepting pattern constrains nothing: adding or removing it is a no-op
        // (never a false tightening or a false response-side breaking loosening)
        String acceptAll = oldY.replace("pattern: '^A[0-9]{3}$'", "pattern: '.*'")
                .replace("pattern: '^B[0-9]{3}$'", "pattern: '.*'");
        assertThat(diff(unconstrained, acceptAll)).isEmpty();
        assertThat(diff(acceptAll, unconstrained)).isEmpty();
    }

    @Test
    @DisplayName("multipleOf direction follows divisibility; unrelated multiples report unknown direction")
    void multipleOfDirection() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {n: {type: integer, multipleOf: 4}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {m: {type: integer, multipleOf: 2}}}
                """;
        // 4 -> 2 on requests: every multiple of 4 is a multiple of 2, so the accepted
        // space grew (loosening, info) — previously mislabeled as a tightening
        String loosened = oldY.replace("multipleOf: 4", "multipleOf: 2");
        List<CompatibilityFinding> loosenedFindings = diff(oldY, loosened);
        assertThat(loosenedFindings).filteredOn(f -> f.code().equals("REQ.CONSTRAINT_LOOSENED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        assertThat(loosenedFindings).noneSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));

        // 2 -> 4 on responses: the promise tightened (subset), which is info
        String tightened = oldY.replace("multipleOf: 2", "multipleOf: 4");
        List<CompatibilityFinding> tightenedFindings = diff(oldY, tightened);
        assertThat(tightenedFindings).filteredOn(f -> f.code().equals("RESP.CONSTRAINT_TIGHTENED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        assertThat(tightenedFindings).noneSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));

        // 3 -> 5 (and 2 -> 5): neither divides the other — explicit unknown direction
        String unrelated = oldY.replace("multipleOf: 4", "multipleOf: 3")
                .replace("multipleOf: 2", "multipleOf: 5");
        List<CompatibilityFinding> unknown = diff(oldY, unrelated);
        assertThat(unknown).filteredOn(f -> f.code().equals("REQ.CONSTRAINT_CHANGED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        assertThat(unknown).filteredOn(f -> f.code().equals("RESP.CONSTRAINT_CHANGED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
    }

    @Test
    @DisplayName("format changes are visible: renamed formats report unknown direction, added/removed are directional")
    void formatSemantics() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {when: {type: string, format: date}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {when: {type: string, format: date}}}
                """;
        String renamed = oldY.replace("format: date", "format: uuid");
        List<CompatibilityFinding> findings = diff(oldY, renamed);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.CONSTRAINT_CHANGED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        assertThat(findings).filteredOn(f -> f.code().equals("RESP.CONSTRAINT_CHANGED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));

        String oldPlain = HEADER + """
                  /b:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {when: {type: string}}}
                """;
        String newFormatted = oldPlain.replace("{type: string}}", "{type: string, format: date}}");
        List<CompatibilityFinding> added = diff(oldPlain, newFormatted);
        assertThat(added).filteredOn(f -> f.code().equals("RESP.CONSTRAINT_TIGHTENED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        List<CompatibilityFinding> removed = diff(newFormatted, oldPlain);
        assertThat(removed).filteredOn(f -> f.code().equals("RESP.CONSTRAINT_LOOSENED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
    }

    @Test
    @DisplayName("array element contracts are directional: added items narrow requests (breaking), removed items weaken response promises (breaking)")
    void itemsAddRemove() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: array}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: array, items: {type: string}}
                """;
        String newY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: array, items: {type: string}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: array}
                """;
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.CONSTRAINT_TIGHTENED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.detail()).contains("array element contract added");
                });
        assertThat(findings).filteredOn(f -> f.code().equals("RESP.CONSTRAINT_LOOSENED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.detail()).contains("array element contract removed");
                });
        assertThat(findings).hasSize(2);
    }

    @Test
    @DisplayName("additionalProperties transitions are directional and object forms are diffed recursively")
    void additionalPropertiesSemantics() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, additionalProperties: true, properties: {x: {type: string}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, additionalProperties: false, properties: {x: {type: string}}}
                """;
        // request: extra keys no longer allowed -> tightening (breaking);
        // response: extra keys now allowed -> loosening (breaking)
        String newY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, additionalProperties: false, properties: {x: {type: string}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, additionalProperties: true, properties: {x: {type: string}}}
                """;
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.CONSTRAINT_TIGHTENED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.detail()).contains("additionalProperties");
                });
        assertThat(findings).filteredOn(f -> f.code().equals("RESP.CONSTRAINT_LOOSENED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
        assertThat(findings).hasSize(2);

        // absent and true both mean "any extra properties": equivalent
        String noFlag = HEADER + """
                  /b:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {x: {type: string}}}
                      responses: {'200': {description: ok}}
                """;
        String trueFlag = noFlag.replace("{type: object, properties:",
                "{type: object, additionalProperties: true, properties:");
        assertThat(diff(noFlag, trueFlag)).isEmpty();
        assertThat(diff(trueFlag, noFlag)).isEmpty();

        // object forms: the schema for extra keys is diffed recursively
        String oldObject = noFlag.replace("{type: object, properties:",
                "{type: object, additionalProperties: {type: string}, properties:");
        String newObject = noFlag.replace("{type: object, properties:",
                "{type: object, additionalProperties: {type: number}, properties:");
        List<CompatibilityFinding> recursive = diff(oldObject, newObject);
        assertThat(recursive).filteredOn(f -> f.code().equals("REQ.TYPE_NARROWED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.fieldPath()).isEqualTo("[*]");
                });
    }

    @Test
    @DisplayName("anyOf branch reordering is cosmetic; branch removal narrows requests (breaking) and branch addition widens responses (breaking)")
    void compositionBranchSemantics() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {v: {anyOf: [{type: string}, {type: number}]}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {w: {anyOf: [{type: string}, {type: number}]}}}
                """;
        // same branches, different order: previously spurious narrowing/widening pairs
        String reordered = oldY
                .replace("properties: {v: {anyOf: [{type: string}, {type: number}]}}",
                        "properties: {v: {anyOf: [{type: number}, {type: string}]}}")
                .replace("properties: {w: {anyOf: [{type: string}, {type: number}]}}",
                        "properties: {w: {anyOf: [{type: number}, {type: string}]}}");
        assertThat(diff(oldY, reordered)).isEmpty();

        // number alternative removed on both sides
        String delta = oldY
                .replace("properties: {v: {anyOf: [{type: string}, {type: number}]}}",
                        "properties: {v: {anyOf: [{type: string}]}}")
                .replace("properties: {w: {anyOf: [{type: string}, {type: number}]}}",
                        "properties: {w: {anyOf: [{type: string}]}}");
        List<CompatibilityFinding> findings = diff(oldY, delta);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.COMPOSITION_BRANCH_REMOVED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.detail()).contains("anyOf").contains("alternative removed");
                });
        assertThat(findings).filteredOn(f -> f.code().equals("RESP.COMPOSITION_BRANCH_REMOVED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));
        assertThat(findings).hasSize(2);

        // boolean alternative added on the response only: the promise space widened
        String widened = oldY.replace("properties: {w: {anyOf: [{type: string}, {type: number}]}}",
                "properties: {w: {anyOf: [{type: string}, {type: number}, {type: boolean}]}}");
        List<CompatibilityFinding> widenedFindings = diff(oldY, widened);
        assertThat(widenedFindings).filteredOn(f -> f.code().equals("RESP.COMPOSITION_BRANCH_ADDED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
        assertThat(widenedFindings).filteredOn(f -> f.code().equals("REQ.COMPOSITION_BRANCH_ADDED"))
                .isEmpty();
    }

    @Test
    @DisplayName("readOnly properties are invisible on the request side; writeOnly on the response side")
    void readOnlyWriteOnly() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, required: [id, name], properties: {id: {type: string, readOnly: true}, name: {type: string}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {secret: {type: string, writeOnly: true}, name: {type: string}}}
                """;
        // the server-managed id disappears from the request contract and the
        // client-supplied secret disappears from the response contract: neither can
        // break anyone, while 'name' still appears on both sides unchanged
        String newY = oldY
                .replace("required: [id, name], properties: {id: {type: string, readOnly: true}, name: {type: string}}",
                        "required: [name], properties: {name: {type: string}}")
                .replace("properties: {secret: {type: string, writeOnly: true}, name: {type: string}}",
                        "properties: {name: {type: string}}");
        assertThat(diff(oldY, newY)).isEmpty();

        // for contrast: removing a plain (unflagged) request property stays breaking
        String unflagged = oldY.replace("{type: string, readOnly: true}", "{type: string}");
        String stripped = unflagged
                .replace("required: [id, name], properties: {id: {type: string}, name: {type: string}}",
                        "required: [name], properties: {name: {type: string}}");
        List<CompatibilityFinding> findings = diff(unflagged, stripped);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.PROPERTY_REMOVED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));
    }

    @Test
    @DisplayName("refactoring locations into components ($ref wrappers) changes nothing on the wire")
    void refactoringIntoComponentsIsInvisible() {
        String inline = HEADER + """
                  /a/{id}:
                    post:
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {x: {type: string}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {y: {type: string}}}
                """;
        String referenced = HEADER + """
                  /a/{id}:
                    post:
                      parameters:
                        - {$ref: '#/components/parameters/Id'}
                      requestBody: {$ref: '#/components/requestBodies/Body'}
                      responses:
                        '200': {$ref: '#/components/responses/Ok'}
                components:
                  parameters:
                    Id: {name: id, in: path, required: true, schema: {type: string}}
                  requestBodies:
                    Body:
                      content:
                        application/json:
                          schema: {type: object, properties: {x: {type: string}}}
                  responses:
                    Ok:
                      description: ok
                      content:
                        application/json:
                          schema: {type: object, properties: {y: {type: string}}}
                """;
        // previously produced a false REQUEST_BODY.REMOVED (breaking) plus
        // PARAM.REMOVED and a schemaless response comparison
        assertThat(diff(inline, referenced)).isEmpty();
        assertThat(diff(referenced, inline)).isEmpty();
    }

    // ------------------------------------------------------- parameter serialization

    @Test
    @DisplayName("PARAM serialization: explode changes alter the wire format for array/object parameters, not for single-valued ones; explicit OpenAPI defaults are wire-neutral")
    void paramSerializationSemantics() {
        String oldY = HEADER + """
                  /a:
                    get:
                      parameters:
                        - {name: ids, in: query, style: form, explode: true, schema: {type: array, items: {type: string}}}
                        - {name: tag, in: query, style: form, explode: true, schema: {type: string}}
                      responses: {'200': {description: ok}}
                """;
        // explode true -> false on the array parameter: "?ids=1&ids=2" becomes "?ids=1,2"
        // on the wire; on the scalar parameter explode has no wire effect
        String newY = oldY.replace("explode: true", "explode: false");
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).filteredOn(f -> f.code().equals("PARAM.SERIALIZATION_CHANGED"))
                .hasSize(2)
                .extracting(CompatibilityFinding::location)
                .allSatisfy(l -> assertThat(l).contains("param:query:"));
        assertThat(findings).filteredOn(f -> f.severity() == Severity.BREAKING)
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.location()).contains("param:query:1");
                    assertThat(f.detail()).contains("explode changed from true to false")
                            .contains("array/object");
                });
        assertThat(findings).filteredOn(f -> f.severity() == Severity.INFO)
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.location()).contains("param:query:2");
                    assertThat(f.detail()).contains("no wire format change");
                });

        // spelling the implicit defaults out explicitly changes nothing on the wire
        String explicitDefaults = HEADER + """
                  /b:
                    get:
                      parameters:
                        - {name: q, in: query, style: form, explode: true, allowReserved: false, schema: {type: string}}
                      responses: {'200': {description: ok}}
                """;
        String implicitDefaults = explicitDefaults
                .replace("style: form, explode: true, allowReserved: false, ", "");
        assertThat(diff(implicitDefaults, explicitDefaults)).isEmpty();
        assertThat(diff(explicitDefaults, implicitDefaults)).isEmpty();
    }

    @Test
    @DisplayName("PARAM serialization: style changes always alter the wire format; disabling allowReserved is breaking, enabling it is informational")
    void paramStyleAndAllowReserved() {
        String oldY = HEADER + """
                  /a/{id}:
                    get:
                      parameters:
                        - {name: id, in: path, required: true, style: matrix, explode: false, schema: {type: string}}
                      responses: {'200': {description: ok}}
                """;
        // ";id=3" (matrix) becomes "/3" (simple): a different wire format for the same
        // parameter — clients built against the old spec send the old shape
        String newY = oldY.replace("style: matrix, explode: false, ", "");
        List<CompatibilityFinding> findings = diff(oldY, newY);
        assertThat(findings).filteredOn(f -> f.code().equals("PARAM.SERIALIZATION_CHANGED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.location()).contains("param:path:1");
                    assertThat(f.detail()).contains("style changed from 'matrix' to 'simple'");
                });

        String oldReserved = HEADER + """
                  /b:
                    get:
                      parameters:
                        - {name: q, in: query, allowReserved: true, schema: {type: string}}
                      responses: {'200': {description: ok}}
                """;
        // permission removed: clients may rely on sending unencoded reserved characters
        List<CompatibilityFinding> disabled = diff(oldReserved,
                oldReserved.replace("allowReserved: true, ", ""));
        assertThat(disabled).filteredOn(f -> f.code().equals("PARAM.SERIALIZATION_CHANGED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.detail()).contains("allowReserved disabled");
                });
        // permission granted: purely additive, informational
        List<CompatibilityFinding> enabled = diff(
                oldReserved.replace("allowReserved: true, ", ""), oldReserved);
        assertThat(enabled).filteredOn(f -> f.code().equals("PARAM.SERIALIZATION_CHANGED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.INFO);
                    assertThat(f.detail()).contains("allowReserved enabled");
                });
    }

    // ------------------------------------------------------- oneOf semantics

    @Test
    @DisplayName("oneOf: reordering is cosmetic; branch removal/addition follow the request/response asymmetry; an evolved branch diffs at its position (exclusivity itself is NOT interpreted)")
    void oneOfSemantics() {
        String oldY = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {v: {oneOf: [{type: string}, {type: number}]}}}
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {type: object, properties: {w: {oneOf: [{type: string}, {type: number}]}}}
                """;
        // same branches, different order: the visible alternative set is unchanged
        String reordered = oldY
                .replace("properties: {v: {oneOf: [{type: string}, {type: number}]}}",
                        "properties: {v: {oneOf: [{type: number}, {type: string}]}}")
                .replace("properties: {w: {oneOf: [{type: string}, {type: number}]}}",
                        "properties: {w: {oneOf: [{type: number}, {type: string}]}}");
        assertThat(diff(oldY, reordered)).isEmpty();

        // removing the number alternative: request side narrows (breaking), response
        // side loses a guaranteed possible shape (info)
        String delta = oldY
                .replace("properties: {v: {oneOf: [{type: string}, {type: number}]}}",
                        "properties: {v: {oneOf: [{type: string}]}}")
                .replace("properties: {w: {oneOf: [{type: string}, {type: number}]}}",
                        "properties: {w: {oneOf: [{type: string}]}}");
        List<CompatibilityFinding> findings = diff(oldY, delta);
        assertThat(findings).filteredOn(f -> f.code().equals("REQ.COMPOSITION_BRANCH_REMOVED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                    assertThat(f.detail()).contains("oneOf").contains("alternative removed");
                });
        assertThat(findings).filteredOn(f -> f.code().equals("RESP.COMPOSITION_BRANCH_REMOVED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO));

        // adding a boolean alternative to the response: the promise space widened
        String widened = oldY.replace("properties: {w: {oneOf: [{type: string}, {type: number}]}}",
                "properties: {w: {oneOf: [{type: string}, {type: number}, {type: boolean}]}}");
        List<CompatibilityFinding> widenedFindings = diff(oldY, widened);
        assertThat(widenedFindings).filteredOn(f -> f.code().equals("RESP.COMPOSITION_BRANCH_ADDED"))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.BREAKING));

        // an evolved branch (same size, second branch narrowed) diffs inside its position
        String evolved = oldY.replace("properties: {v: {oneOf: [{type: string}, {type: number}]}}",
                "properties: {v: {oneOf: [{type: string}, {type: integer}]}}");
        List<CompatibilityFinding> evolvedFindings = diff(oldY, evolved);
        assertThat(evolvedFindings).filteredOn(f -> f.code().equals("REQ.TYPE_NARROWED"))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.fieldPath()).isEqualTo("v[1]");
                    assertThat(f.severity()).isEqualTo(Severity.BREAKING);
                });
    }
}
