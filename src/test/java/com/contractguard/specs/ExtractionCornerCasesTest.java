package com.contractguard.specs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Extraction-level corner cases over synthetic specs (real SpecParser in, effective
 * maps out): circular $refs, allOf restructuring, {$ref}-wrapped locations,
 * media-type normalization and media-type (content) parameters. Each case pins a
 * wire-neutral representation that previously leaked raw artifacts into the effective
 * maps (false findings) or vanished silently (missing scopes).
 */
class ExtractionCornerCasesTest {

    private final SpecParser parser = new SpecParser();

    private static final String HEADER = """
            openapi: 3.0.3
            info: {title: T, version: 1.0.0}
            paths:
            """;

    @Test
    @DisplayName("circular $refs terminate with opaque wire-shaped markers; renaming the cyclic component is invisible")
    void circularRefsAreCutAndRenameNeutral() {
        String v1 = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/Node'}
                      responses: {'200': {description: ok}}
                components:
                  schemas:
                    Node:
                      type: object
                      properties:
                        value: {type: string}
                        next: {$ref: '#/components/schemas/Node'}
                """;
        // same wire contract, different component name
        String v2 = v1.replace("Node", "ListNode");

        var v1Effective = parser.parse(v1).getEffectiveSchemas();
        var v2Effective = parser.parse(v2).getEffectiveSchemas();

        assertThat(v2Effective).isEqualTo(v1Effective);
        // no raw pointer survives expansion
        assertThat(v1Effective.values()).noneSatisfy(s -> assertThat(s).contains("$ref"));
        // the cycle is cut explicitly, at a wire-visible path (component-name free)
        assertThat(v1Effective.values()).anySatisfy(
                s -> assertThat(s).contains("\"x-circular\":\"next\""));
    }

    @Test
    @DisplayName("a self-referencing component used directly as a response schema is cut at the same wire path")
    void circularMarkerPathIsWireVisible() {
        String y = HEADER + """
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: {$ref: '#/components/schemas/Tree'}
                components:
                  schemas:
                    Tree:
                      type: object
                      properties:
                        name: {type: string}
                        children:
                          type: array
                          items: {$ref: '#/components/schemas/Tree'}
                """;
        String sameWireShape = y.replace("Tree", "Branch");

        var effective = parser.parse(y).getEffectiveSchemas();
        assertThat(parser.parse(sameWireShape).getEffectiveSchemas()).isEqualTo(effective);
        assertThat(effective.values()).anySatisfy(
                s -> assertThat(s).contains("\"x-circular\":\"children.[]\""));
    }

    @Test
    @DisplayName("moving fields into allOf branches is wire-neutral; a conflicting allOf keeps its list")
    void allOfMerge() {
        String inline = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object, required: [a], properties: {a: {type: string}, b: {type: number}}}
                      responses: {'200': {description: ok}}
                """;
        String restructured = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              allOf:
                                - {type: object, required: [a], properties: {a: {type: string}}}
                                - {type: object, properties: {b: {type: number}}}
                      responses: {'200': {description: ok}}
                """;

        assertThat(parser.parse(restructured).getEffectiveSchemas())
                .isEqualTo(parser.parse(inline).getEffectiveSchemas());

        // two branches that contradict each other about the same property are NOT
        // merged: the allOf list survives so nothing is silently guessed away
        String conflicting = restructured.replace(
                "- {type: object, properties: {b: {type: number}}}",
                "- {type: object, properties: {a: {type: integer}}}");
        assertThat(parser.parse(conflicting).getEffectiveSchemas()
                .get("POST /a :: requestBody:application/json")).contains("\"allOf\"");
    }

    @Test
    @DisplayName("$ref-wrapped parameters, requestBodies and responses produce the same scopes as their inline forms")
    void refWrappedLocations() {
        String inline = HEADER + """
                  /a/{id}:
                    post:
                      parameters:
                        - {name: id, in: path, required: true, schema: {type: string}}
                        - {name: limit, in: query, schema: {type: integer}}
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
                        - {$ref: '#/components/parameters/Limit'}
                      requestBody: {$ref: '#/components/requestBodies/Body'}
                      responses:
                        '200': {$ref: '#/components/responses/Ok'}
                components:
                  parameters:
                    Id: {name: id, in: path, required: true, schema: {type: string}}
                    Limit: {name: limit, in: query, schema: {type: integer}}
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

        var inlineEffective = parser.parse(inline).getEffectiveSchemas();
        var referencedEffective = parser.parse(referenced).getEffectiveSchemas();

        assertThat(referencedEffective).isEqualTo(inlineEffective);
        // every location is present, including the previously-dropped wrappers; path
        // placeholders are normalized to their positional shape ({0}, not {id})
        assertThat(referencedEffective.keySet()).contains(
                "POST /a/{0} :: param:path:1",
                "POST /a/{0} :: param:query:1",
                "POST /a/{0} :: requestBody:application/json",
                "POST /a/{0} :: response:200:application/json");
    }

    @Test
    @DisplayName("media-type parameters (charset, case) stay one wire location")
    void mediaNormalization() {
        String plain = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object}
                      responses:
                        '200':
                          description: ok
                          content:
                            APPLICATION/JSON:
                              schema: {type: object}
                """;
        String decorated = HEADER + """
                  /a:
                    post:
                      requestBody:
                        content:
                          'application/json; charset=utf-8':
                            schema: {type: object}
                      responses:
                        '200':
                          description: ok
                          content:
                            'Application/JSON; q=0.9':
                              schema: {type: object}
                """;

        var plainEffective = parser.parse(plain).getEffectiveSchemas();
        var decoratedEffective = parser.parse(decorated).getEffectiveSchemas();

        assertThat(decoratedEffective).isEqualTo(plainEffective);
        assertThat(decoratedEffective.keySet())
                .contains("POST /a :: requestBody:application/json",
                        "POST /a :: response:200:application/json");
    }

    @Test
    @DisplayName("media-type (content) parameters keep their accepted media types inside the envelope")
    void contentParameter() {
        String y = HEADER + """
                  /a:
                    post:
                      parameters:
                        - name: payload
                          in: query
                          content:
                            application/json:
                              schema: {type: object, properties: {x: {type: string}}}
                      responses: {'200': {description: ok}}
                """;

        var effective = parser.parse(y).getEffectiveSchemas();
        assertThat(effective).containsKey("POST /a :: param:query:1");
        assertThat(effective.get("POST /a :: param:query:1")).isEqualTo(
                "{\"required\":false,\"schema\":{\"x-content\":{\"application/json\":"
                        + "{\"properties\":{\"x\":{\"type\":\"string\"}},\"type\":\"object\"}}}}");
    }

    @Test
    @DisplayName("serialization attributes are recorded as deviations from the OpenAPI defaults; explicit defaults and non-query allowReserved stay invisible")
    void serializationEnvelope() {
        // explode:false on a query parameter deviates from the form-style default (true)
        String explodeFalse = HEADER + """
                  /a:
                    get:
                      parameters:
                        - {name: ids, in: query, style: form, explode: false, schema: {type: array, items: {type: string}}}
                      responses: {'200': {description: ok}}
                """;
        assertThat(parser.parse(explodeFalse).getEffectiveSchemas()
                .get("GET /a :: param:query:1")).isEqualTo(
                "{\"required\":false,\"schema\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},"
                        + "\"x-serialization\":{\"explode\":false}}");

        // style:matrix + explode:true on a path parameter deviates from simple/false
        String matrix = HEADER + """
                  /b/{id}:
                    get:
                      parameters:
                        - {name: id, in: path, required: true, style: matrix, explode: true, schema: {type: string}}
                      responses: {'200': {description: ok}}
                """;
        assertThat(parser.parse(matrix).getEffectiveSchemas()
                .get("GET /b/{0} :: param:path:1")).isEqualTo(
                "{\"required\":true,\"schema\":{\"type\":\"string\"},"
                        + "\"x-serialization\":{\"explode\":true,\"style\":\"matrix\"}}");

        // allowReserved is query-only: recorded for query...
        String reserved = HEADER + """
                  /c:
                    get:
                      parameters:
                        - {name: q, in: query, allowReserved: true, schema: {type: string}}
                      responses: {'200': {description: ok}}
                """;
        assertThat(parser.parse(reserved).getEffectiveSchemas()
                .get("GET /c :: param:query:1")).isEqualTo(
                "{\"required\":false,\"schema\":{\"type\":\"string\"},"
                        + "\"x-serialization\":{\"allowReserved\":true}}");

        // ...and ignored for non-query parameters (OAS 3.0: allowReserved applies to query)
        String reservedOnPath = matrix.replace("style: matrix, explode: true, ", "")
                .replace("schema: {type: string}", "allowReserved: true, schema: {type: string}");
        assertThat(parser.parse(reservedOnPath).getEffectiveSchemas()
                .get("GET /b/{0} :: param:path:1")).isEqualTo(
                "{\"required\":true,\"schema\":{\"type\":\"string\"}}");

        // spelling the implicit defaults out explicitly is wire-neutral: no envelope change
        String explicitDefaults = HEADER + """
                  /d:
                    get:
                      parameters:
                        - {name: q, in: query, style: form, explode: true, allowReserved: false, schema: {type: string}}
                      responses: {'200': {description: ok}}
                """;
        assertThat(parser.parse(explicitDefaults).getEffectiveSchemas()
                .get("GET /d :: param:query:1")).isEqualTo(
                "{\"required\":false,\"schema\":{\"type\":\"string\"}}");
    }

    @Test
    @DisplayName("a $ref-wrapped parameter keeps its serialization attributes in the envelope")
    void refWrappedSerialization() {
        String y = HEADER + """
                  /a:
                    get:
                      parameters:
                        - {$ref: '#/components/parameters/Ids'}
                      responses: {'200': {description: ok}}
                components:
                  parameters:
                    Ids: {name: ids, in: query, style: form, explode: false, schema: {type: array, items: {type: string}}}
                """;

        assertThat(parser.parse(y).getEffectiveSchemas()
                .get("GET /a :: param:query:1")).isEqualTo(
                "{\"required\":false,\"schema\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},"
                        + "\"x-serialization\":{\"explode\":false}}");
    }
}
