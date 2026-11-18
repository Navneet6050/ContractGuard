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
}
