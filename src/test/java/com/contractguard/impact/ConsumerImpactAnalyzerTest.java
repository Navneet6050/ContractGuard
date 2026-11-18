package com.contractguard.impact;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.contractguard.compatibility.CompatibilityEngine;
import com.contractguard.compatibility.CompatibilityFinding;
import com.contractguard.consumers.ConsumerDeclaration;
import com.contractguard.consumers.ConsumerParser;
import com.contractguard.specs.SpecDocument;
import com.contractguard.specs.SpecParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tier-classification tests over synthetic spec pairs. Everything runs through
 * the REAL SpecParser → CompatibilityEngine pipeline; the analyzer must never invent
 * usage and must explain every classification in its reason text.
 */
class ConsumerImpactAnalyzerTest {

    private final SpecParser specParser = new SpecParser();
    private final CompatibilityEngine engine = new CompatibilityEngine();
    private final ConsumerImpactAnalyzer analyzer = new ConsumerImpactAnalyzer();
    private final ConsumerParser consumerParser = new ConsumerParser();

    private static final String OLD = """
            openapi: 3.0.3
            info: {title: T, version: 1.0.0}
            paths:
              /quotes/{id}:
                get:
                  parameters: [{name: id, in: path, required: true, schema: {type: string}}]
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            required: [quoteId, premium]
                            properties:
                              quoteId: {type: string}
                              status: {type: string}
                              premium:
                                type: object
                                properties:
                                  total: {type: number}
                                  currency: {type: string}
                                  breakdown:
                                    type: array
                                    items:
                                      type: object
                                      required: [label, amount]
                                      properties:
                                        label: {type: string}
                                        amount: {type: number}
            """;

    private ImpactSummary analyze(String service, String oldYaml, String newYaml, String consumerYaml) {
        SpecDocument oldDoc = specParser.parse(oldYaml);
        SpecDocument newDoc = specParser.parse(newYaml);
        List<CompatibilityFinding> findings = engine.diff(oldDoc.getEffectiveSchemas(),
                newDoc.getEffectiveSchemas(), oldDoc.getPathAliases(), newDoc.getPathAliases());
        ConsumerDeclaration consumer = consumerParser.parse(consumerYaml);
        return analyzer.analyze(service, oldDoc, newDoc, findings, List.of(consumer));
    }

    private static ConsumerImpact single(ImpactSummary summary) {
        assertThat(summary.consumers()).hasSize(1);
        return summary.consumers().get(0);
    }

    // ------------------------------------------------------------- response side

    @Test
    @DisplayName("RESP.PROPERTY_REMOVED: exact reader AFFECTED, sibling reader NONE, user with no declared reads POSSIBLE")
    void responseRemovalTiers() {
        String newY = OLD.replace("amount: {type: number}", "").replace("required: [label, amount]", "required: [label]");

        ImpactSummary reader = analyze("svc", OLD, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  reads: ["premium.breakdown[].amount"]
                """);
        assertThat(single(reader).level()).isEqualTo(ImpactLevel.AFFECTED);
        assertThat(single(reader).impacts().get(0).reason()).contains("premium.breakdown[].amount");

        ImpactSummary sibling = analyze("svc", OLD, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  reads: ["premium.breakdown[].label"]
                """);
        assertThat(single(sibling).level()).isEqualTo(ImpactLevel.NONE);
        assertThat(single(sibling).impacts().get(0).reason()).contains("do not include");

        // user of the operation with NO declared reads: dependence unknown → POSSIBLE
        ImpactSummary silent = analyze("svc", OLD, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  sends: [id]
                """);
        assertThat(single(silent).level()).isEqualTo(ImpactLevel.POSSIBLE);
        assertThat(single(silent).impacts().get(0).reason()).contains("cannot be ruled out");
    }

    @Test
    @DisplayName("ancestor matching: reader of 'premium.breakdown' / 'premium' is hit by an item-field removal")
    void ancestorAndSiblingMatching() {
        String newY = OLD.replace("amount: {type: number}", "").replace("required: [label, amount]", "required: [label]");

        ImpactSummary arrayReader = analyze("svc", OLD, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  reads: [premium.breakdown]
                """);
        assertThat(single(arrayReader).level()).isEqualTo(ImpactLevel.AFFECTED);

        ImpactSummary objectReader = analyze("svc", OLD, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  reads: [premium]
                """);
        assertThat(single(objectReader).level()).isEqualTo(ImpactLevel.AFFECTED);
    }

    @Test
    @DisplayName("RESP.ENUM_WIDENED (advisory widening): declared reader lands in POSSIBLE, never AFFECTED")
    void wideningIsPossibleNotAffected() {
        String newY = OLD.replace("status: {type: string}", "status: {type: string, enum: [A, B]}");
        ImpactSummary reader = analyze("svc", OLD, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  reads: [status]
                """);
        assertThat(single(reader).level()).isEqualTo(ImpactLevel.POSSIBLE);
        assertThat(single(reader).impacts().get(0).findingCode()).isEqualTo("RESP.ENUM_WIDENED");
        assertThat(single(reader).impacts().get(0).reason()).contains("may return values");
    }

    // ------------------------------------------------------------- request side

    @Test
    @DisplayName("REQ.PROPERTY_REMOVED: sender of the field AFFECTED; sender of siblings NONE; empty sends POSSIBLE")
    void requestRemovalTiers() {
        String oldY = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /quotes:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                make: {type: string}
                                model: {type: string}
                      responses: {'200': {description: ok}}
                """;
        String newY = oldY.replace("model: {type: string}", "");

        ImpactSummary sender = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: POST
                  path: /quotes
                  sends: [make, model]
                """);
        assertThat(single(sender).level()).isEqualTo(ImpactLevel.AFFECTED);
        assertThat(single(sender).impacts().get(0).reason()).contains("you send 'model'");

        ImpactSummary siblingSender = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: POST
                  path: /quotes
                  sends: [make]
                """);
        assertThat(single(siblingSender).level()).isEqualTo(ImpactLevel.NONE);
        assertThat(single(siblingSender).impacts().get(0).reason()).contains("do not include");

        ImpactSummary silent = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: POST
                  path: /quotes
                  reads: [x]
                """);
        assertThat(single(silent).level()).isEqualTo(ImpactLevel.POSSIBLE);
    }

    @Test
    @DisplayName("REQ.REQUIRED_ADDED inverse logic: exact/subfield sender NONE, proven non-sender AFFECTED, empty sends AFFECTED")
    void requiredAddedInverse() {
        String oldY = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /endorsements:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                type: {type: string}
                                details: {type: string}
                      responses: {'200': {description: ok}}
                """;
        String newY = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /endorsements:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required: [requestedBy]
                              properties:
                                type: {type: string}
                                details: {type: string}
                                requestedBy: {type: string}
                      responses: {'200': {description: ok}}
                """;

        ImpactSummary compliant = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: POST
                  path: /endorsements
                  sends: [type, requestedBy]
                """);
        assertThat(single(compliant).level()).isEqualTo(ImpactLevel.NONE);
        assertThat(single(compliant).impacts())
                .filteredOn(i -> i.findingCode().equals("REQ.REQUIRED_ADDED"))
                .hasSize(1)
                .allSatisfy(i -> assertThat(i.reason()).contains("already declares sending"));

        // sending a subfield (requestedBy.name) implies the parent object is sent → compliant
        ImpactSummary subfieldSender = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: POST
                  path: /endorsements
                  sends: [requestedBy.name]
                """);
        assertThat(single(subfieldSender).level()).isEqualTo(ImpactLevel.NONE);

        // sibling-name sender: proven NOT to send the now-required field → AFFECTED
        ImpactSummary nonSender = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: POST
                  path: /endorsements
                  sends: [type, details]
                """);
        assertThat(single(nonSender).level()).isEqualTo(ImpactLevel.AFFECTED);
        assertThat(single(nonSender).impacts())
                .filteredOn(i -> i.findingCode().equals("REQ.REQUIRED_ADDED"))
                .hasSize(1)
                .allSatisfy(i -> assertThat(i.reason()).contains("now required"));

        // nothing declared on the request side → not provably compliant → AFFECTED
        ImpactSummary emptySends = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: POST
                  path: /endorsements
                """);
        assertThat(single(emptySends).level()).isEqualTo(ImpactLevel.AFFECTED);
    }

    @Test
    @DisplayName("rename: readers of the old path AND of the derived new path are AFFECTED; sibling reader NONE")
    void renameMatching() {
        String newY = OLD.replace("amount: {type: number}", "value: {type: number}")
                .replace("required: [label, amount]", "required: [label, value]");

        ImpactSummary oldReader = analyze("svc", OLD, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  reads: ["premium.breakdown[].amount"]
                """);
        assertThat(single(oldReader).level()).isEqualTo(ImpactLevel.AFFECTED);
        assertThat(single(oldReader).impacts().get(0).reason()).contains("renamed to 'value'");

        ImpactSummary newReader = analyze("svc", OLD, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  reads: ["premium.breakdown[].value"]
                """);
        assertThat(single(newReader).level()).isEqualTo(ImpactLevel.AFFECTED);

        ImpactSummary sibling = analyze("svc", OLD, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  reads: ["premium.breakdown[].label"]
                """);
        assertThat(single(sibling).level()).isEqualTo(ImpactLevel.NONE);
    }

    // ------------------------------------------------------------- parameters & operations

    @Test
    @DisplayName("PARAM.REQUIRED_ADDED: binder not sending the param AFFECTED; pre-adapted binder NONE")
    void parameterImpact() {
        String oldY = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /cache:
                    delete:
                      responses: {'204': {description: cleared}}
                """;
        String newY = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /cache:
                    delete:
                      parameters: [{name: region, in: query, required: true, schema: {type: string}}]
                      responses: {'204': {description: cleared}}
                """;

        ImpactSummary binder = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: DELETE
                  path: /cache
                """);
        assertThat(single(binder).level()).isEqualTo(ImpactLevel.AFFECTED);
        assertThat(single(binder).impacts().get(0).findingCode()).isEqualTo("PARAM.REQUIRED_ADDED");
        assertThat(single(binder).impacts().get(0).reason()).contains("'region'");

        ImpactSummary preAdapted = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: DELETE
                  path: /cache
                  sends: [region]
                """);
        assertThat(single(preAdapted).level()).isEqualTo(ImpactLevel.NONE);
        assertThat(single(preAdapted).impacts().get(0).reason()).contains("already declares sending");
    }

    @Test
    @DisplayName("PARAM name resolution: consumer 'sends: [region]' matches even though envelopes drop names")
    void parameterNameResolvedThroughSpecsModule() {
        // param becomes required (existed before): name resolved from BOTH docs
        String oldY = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /cache:
                    delete:
                      parameters: [{name: region, in: query, required: false, schema: {type: string}}]
                      responses: {'204': {description: cleared}}
                """;
        String newY = oldY.replace("required: false", "required: true");

        ImpactSummary nonSender = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: DELETE
                  path: /cache
                  sends: []
                """);
        assertThat(single(nonSender).level()).isEqualTo(ImpactLevel.AFFECTED);

        ImpactSummary sender = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: DELETE
                  path: /cache
                  sends: [region]
                """);
        assertThat(single(sender).level()).isEqualTo(ImpactLevel.NONE);
    }

    @Test
    @DisplayName("OPERATION.REMOVED: bound consumer AFFECTED")
    void operationRemoval() {
        String newY = OLD.replace("  /quotes/{id}:", "  /renamed/{id}:");
        ImpactSummary summary = analyze("svc", OLD, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  reads: [status]
                """);
        assertThat(single(summary).level()).isEqualTo(ImpactLevel.AFFECTED);
        assertThat(single(summary).impacts().get(0).findingCode()).isEqualTo("OPERATION.REMOVED");
        assertThat(single(summary).impacts().get(0).reason()).contains("removed");
    }

    // ------------------------------------------------------------- isolation & info

    @Test
    @DisplayName("cross-operation isolation: a breaking change on POST /quotes never touches a GET-only consumer")
    void crossOperationIsolation() {
        String oldY = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /quotes:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, properties: {make: {type: string}}}
                      responses: {'200': {description: ok}}
                  /other:
                    get:
                      responses: {'200': {description: ok}}
                """;
        String newY = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /quotes:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, required: [cc], properties: {make: {type: string}, cc: {type: integer}}}
                      responses: {'200': {description: ok}}
                  /other:
                    get:
                      responses: {'200': {description: ok}}
                """;

        ImpactSummary summary = analyze("svc", oldY, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /other
                """);
        ConsumerImpact impact = single(summary);
        assertThat(impact.level()).isEqualTo(ImpactLevel.NONE);
        assertThat(impact.impacts()).isEmpty();
        assertThat(impact.reason()).contains("not touched by any compatibility finding");
    }

    @Test
    @DisplayName("non-breaking findings never rise above NONE and are still surfaced as informational")
    void infoFindings() {
        String newY = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /quotes/{id}:
                    get:
                      parameters: [{name: id, in: path, required: true, schema: {type: string}}]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [quoteId, premium]
                                properties:
                                  quoteId: {type: string}
                                  status: {type: string}
                                  premium:
                                    type: object
                                    properties:
                                      total: {type: number}
                                      currency: {type: string}
                                      taxes: {type: number}
                                      breakdown:
                                        type: array
                                        items:
                                          type: object
                                          required: [label, amount]
                                          properties:
                                            label: {type: string}
                                            amount: {type: number}
                """;
        ImpactSummary summary = analyze("svc", OLD, newY, """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  reads: [premium.total]
                """);
        ConsumerImpact impact = single(summary);
        assertThat(impact.level()).isEqualTo(ImpactLevel.NONE);
        assertThat(impact.impacts()).hasSize(1);
        assertThat(impact.impacts().get(0).findingSeverity()).isEqualTo("info");
        assertThat(impact.impacts().get(0).reason()).contains("informational");
    }

    @Test
    @DisplayName("consumers of other services are never touched (service slug join)")
    void otherServiceIsolation() {
        ImpactSummary summary = analyze("other-svc", OLD,
                OLD.replace("status: {type: string}", "status: {type: string, enum: [A]}"), """
                consumer: c
                uses:
                - provider: svc
                  method: GET
                  path: /quotes/{quoteId}
                  reads: [status]
                """);
        ConsumerImpact impact = single(summary);
        assertThat(impact.level()).isEqualTo(ImpactLevel.NONE);
        assertThat(impact.impacts()).isEmpty();
        assertThat(impact.reason()).contains("declares no usage of 'other-svc'");
    }
}

