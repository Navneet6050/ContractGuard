package com.contractguard.specs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deprecation/x-sunset scanning against the actual policy fixtures (which carry the
 * kit's planted lifecycle data) plus synthetic cases.
 */
class DeprecationScannerTest {

    private final SpecParser parser = new SpecParser();

    @Test
    @DisplayName("policy v1.1.0: deprecated fields found with parsed x-sunset dates")
    void policyDeprecationsFound() {
        SpecDocument doc = parser.parse(Fixtures.spec("policy-api", "2-deprecations.yaml"));
        assertThat(doc.getDeprecations()).hasSize(2);

        var byPath = doc.getDeprecations().stream()
                .collect(java.util.stream.Collectors.toMap(DeprecationRecord::humanPath, r -> r));

        assertThat(byPath).containsKeys("Policy.legacyCode", "Holder.phone");

        assertThat(byPath.get("Policy.legacyCode").sunset()).contains(java.time.LocalDate.parse("2026-11-01"));
        assertThat(byPath.get("Policy.legacyCode").sunsetRaw()).contains("2026-11-01");

        assertThat(byPath.get("Holder.phone").sunset()).contains(java.time.LocalDate.parse("2027-06-30"));
    }

    @Test
    @DisplayName("policy v1.0.0: nothing deprecated")
    void policyV1Clean() {
        SpecDocument doc = parser.parse(Fixtures.spec("policy-api", "1-initial.yaml"));
        assertThat(doc.getDeprecations()).isEmpty();
    }

    @Test
    @DisplayName("synthetic: deprecated flag without x-sunset is recorded with empty date")
    void deprecatedWithoutSunset() {
        String yaml = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths: {}
                components:
                  schemas:
                    Thing:
                      type: object
                      properties:
                        old:
                          type: string
                          deprecated: true
                """;
        var records = parser.parse(yaml).getDeprecations();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).humanPath()).isEqualTo("Thing.old");
        assertThat(records.get(0).sunset()).isEmpty();
        assertThat(records.get(0).sunsetRaw()).isEmpty();
    }

    @Test
    @DisplayName("synthetic: deprecated item inside an array renders [] field path")
    void deprecatedInsideArray() {
        String yaml = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths: {}
                components:
                  schemas:
                    Line:
                      type: object
                      required: [label]
                      properties:
                        label: {type: string}
                    Bundle:
                      type: object
                      properties:
                        lines:
                          type: array
                          items:
                            type: object
                            properties:
                              note:
                                type: string
                                deprecated: true
                """;
        var records = parser.parse(yaml).getDeprecations();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).humanPath()).isEqualTo("Bundle.lines[].note");
    }

    @Test
    @DisplayName("synthetic: malformed x-sunset dates do not crash scanning")
    void malformedSunsetTolerated() {
        String yaml = """
                openapi: 3.0.3
                info: {title: T, version: 1.0.0}
                paths: {}
                components:
                  schemas:
                    Thing:
                      type: object
                      properties:
                        old:
                          type: string
                          deprecated: true
                          x-sunset: 'not-a-date'
                """;
        var records = parser.parse(yaml).getDeprecations();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).sunset()).isEmpty();
        assertThat(records.get(0).sunsetRaw()).contains("not-a-date");
    }
}
