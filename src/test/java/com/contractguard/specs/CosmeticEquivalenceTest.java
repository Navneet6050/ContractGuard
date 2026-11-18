package com.contractguard.specs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The starter kit's deliberate trap: quote v1.0.0 → v1.1.0 renames components
 * (Premium→PremiumDetails, BreakdownItem→PriceLine), inlines Vehicle/Rider into
 * QuoteRequest and renames the path parameter {id}→{quoteId}. The wire contract does
 * not change, so the effective-schema maps must be EQUAL while the whole-document
 * canonical forms must DIFFER (the documents are genuinely different).
 */
class CosmeticEquivalenceTest {

    private final SpecParser parser = new SpecParser();

    @Test
    @DisplayName("quote v1.0.0 and v1.1.0 have equivalent effective wire contracts")
    void effectiveContractsAreEquivalent() {
        Map<String, String> v1 = parser.parse(Fixtures.spec("quote-api", "1-initial.yaml")).getEffectiveSchemas();
        Map<String, String> v11 = parser.parse(Fixtures.spec("quote-api", "2-reorganised.yaml")).getEffectiveSchemas();

        assertThat(v11.keySet()).isEqualTo(v1.keySet());
        for (String key : v1.keySet()) {
            assertThat(v11.get(key))
                    .as("effective schema for %s must be identical after cosmetic reorg", key)
                    .isEqualTo(v1.get(key));
        }
    }

    @Test
    @DisplayName("canonical whole-document forms differ (content really changed)")
    void canonicalFormsDiffer() {
        String v1 = parser.parse(Fixtures.spec("quote-api", "1-initial.yaml")).getCanonicalJson();
        String v11 = parser.parse(Fixtures.spec("quote-api", "2-reorganised.yaml")).getCanonicalJson();
        assertThat(v11).isNotEqualTo(v1);
    }

    @Test
    @DisplayName("genuinely different versions do not collapse to the same effective map")
    void realChangesAreVisible() {
        Map<String, String> v2 = parser.parse(Fixtures.spec("quote-api", "3-price-lines.yaml")).getEffectiveSchemas();
        Map<String, String> v11 = parser.parse(Fixtures.spec("quote-api", "2-reorganised.yaml")).getEffectiveSchemas();
        // v2 renames breakdown[].amount → value and adds EXPIRED: maps must differ
        assertThat(v2).isNotEqualTo(v11);
    }

    @Test
    @DisplayName("synthetic: inline object and $ref with identical shape are equivalent")
    void syntheticInlineVsRef() {
        String withRef = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /things:
                    post:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Thing'
                components:
                  schemas:
                    Thing:
                      type: object
                      required: [a]
                      properties:
                        a: {type: string}
                """;
        String inlined = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /things:
                    post:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [a]
                                properties:
                                  a: {type: string}
                """;
        var a = parser.parse(withRef).getEffectiveSchemas();
        var b = parser.parse(inlined).getEffectiveSchemas();
        assertThat(b).isEqualTo(a);
    }

    @Test
    @DisplayName("synthetic: required-array order is irrelevant to equivalence")
    void syntheticRequiredOrderIrrelevant() {
        String docA = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths:
                  /x:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required: [b, a]
                              properties:
                                a: {type: string}
                                b: {type: string}
                      responses: {'200': {description: ok}}
                """;
        String docB = docA.replace("required: [b, a]", "required: [a, b]");
        assertThat(parser.parse(docB).getEffectiveSchemas())
                .isEqualTo(parser.parse(docA).getEffectiveSchemas());
    }
}
