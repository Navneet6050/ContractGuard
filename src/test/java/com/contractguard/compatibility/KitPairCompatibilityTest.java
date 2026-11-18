package com.contractguard.compatibility;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.contractguard.compatibility.CompatibilityFinding;
import com.contractguard.compatibility.CompatibilityEngine;
import com.contractguard.compatibility.Severity;
import com.contractguard.specs.Fixtures;
import com.contractguard.specs.SpecDocument;
import com.contractguard.specs.SpecParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end compatibility analysis over the ACTUAL starter-kit spec pairs, using the
 * effective-schema output of the specs module (never raw YAML). Each test name states
 * the transition; expectations are derived from the contract content, not filenames.
 */
class KitPairCompatibilityTest {

    private final SpecParser parser = new SpecParser();
    private final CompatibilityEngine engine = new CompatibilityEngine();

    private List<CompatibilityFinding> diffKit(String service, String oldFile, String newFile) {
        SpecDocument oldDoc = parser.parse(Fixtures.spec(service, oldFile));
        SpecDocument newDoc = parser.parse(Fixtures.spec(service, newFile));
        return engine.diff(oldDoc.getEffectiveSchemas(), newDoc.getEffectiveSchemas(),
                oldDoc.getPathAliases(), newDoc.getPathAliases());
    }

    @Test
    @DisplayName("quote 1.0.0 → 1.1.0: cosmetic reorganization produces ZERO findings")
    void cosmeticReorgIsSilent() {
        List<CompatibilityFinding> findings = diffKit("quote-api", "1-initial.yaml", "2-reorganised.yaml");
        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("quote 1.1.0 → 2.0.0: rename + required query param + enum widened, all breaking")
    void quote2DetectsRealBreaks() {
        List<CompatibilityFinding> findings = diffKit("quote-api", "2-reorganised.yaml", "3-price-lines.yaml");

        List<CompatibilityFinding> breaking = bySeverity(findings, Severity.BREAKING);
        List<CompatibilityFinding> info = bySeverity(findings, Severity.INFO);
        assertThat(info).isEmpty();

        // 2 locations carry the breakdown rename + status widening; 1 param addition
        assertThat(breaking).hasSize(5);

        List<CompatibilityFinding> renames = byCode(findings, "RESP.PROPERTY_RENAMED");
        assertThat(renames).hasSize(2);
        assertThat(renames).allSatisfy(f -> {
            assertThat(f.fieldPath()).isEqualTo("premium.breakdown[].amount");
            assertThat(f.detail()).contains("'amount'").contains("'value'");
        });
        assertThat(renames).extracting(CompatibilityFinding::method)
                .containsOnly("POST", "GET");

        List<CompatibilityFinding> paramAdded = byCode(findings, "PARAM.REQUIRED_ADDED");
        assertThat(paramAdded).hasSize(1);
        assertThat(paramAdded.get(0).path()).isEqualTo("/internal/pricing-cache");
        assertThat(paramAdded.get(0).location()).contains("param:query:1");

        List<CompatibilityFinding> enumWidened = byCode(findings, "RESP.ENUM_WIDENED");
        assertThat(enumWidened).hasSize(2);
        assertThat(enumWidened).allSatisfy(f -> assertThat(f.fieldPath()).isEqualTo("status"));
    }

    @Test
    @DisplayName("policy 1.1.0 → 2.0.0: legacyCode and Holder.phone removals, each on both Policy locations")
    void policy2DetectsRemovals() {
        List<CompatibilityFinding> findings = diffKit("policy-api", "2-deprecations.yaml", "3-remove-old-fields.yaml");

        List<CompatibilityFinding> breaking = bySeverity(findings, Severity.BREAKING);
        assertThat(bySeverity(findings, Severity.INFO)).isEmpty();
        assertThat(breaking).hasSize(4);
        assertThat(breaking).allSatisfy(f -> assertThat(f.code()).isEqualTo("RESP.PROPERTY_REMOVED"));

        // both fields are removed on BOTH Policy-bearing responses; the array response
        // (/policies) prefixes item paths with "[]"
        assertThat(breaking).filteredOn(f -> f.fieldPath().endsWith("legacyCode")).hasSize(2);
        assertThat(breaking).filteredOn(f -> f.fieldPath().endsWith("holder.phone")).hasSize(2);
        assertThat(breaking).filteredOn(f -> f.path().equals("/policies")).hasSize(2);
        assertThat(breaking).filteredOn(f -> f.path().equals("/policies/{policyId}")).hasSize(2);
    }

    @Test
    @DisplayName("policy 2.0.0 → 2.1.0: required requestedBy + VEHICLE enum removal (breaking), requestedBy property added (info)")
    void policy21DetectsStealthBreaks() {
        List<CompatibilityFinding> findings = diffKit("policy-api", "3-remove-old-fields.yaml", "4-endorsement-rules.yaml");

        List<CompatibilityFinding> breaking = bySeverity(findings, Severity.BREAKING);
        assertThat(breaking).hasSize(2);
        assertThat(breaking).filteredOn(f -> f.code().equals("REQ.REQUIRED_ADDED")
                && f.fieldPath().equals("requestedBy")).hasSize(1);
        assertThat(breaking).filteredOn(f -> f.code().equals("REQ.ENUM_NARROWED")
                && f.fieldPath().equals("type")
                && f.detail().contains("VEHICLE")).hasSize(1);
        assertThat(byCode(findings, "REQ.PROPERTY_ADDED")).hasSize(1);
        assertThat(findings).allSatisfy(f -> assertThat(f.path()).isEqualTo("/policies/{policyId}/endorsements"));
    }

    @Test
    @DisplayName("claims 1.1.0 → 2.0.0: amountApproved no longer guaranteed (breaking) + 202 documented (info)")
    void claims2DetectsRequiredRemoval() {
        List<CompatibilityFinding> findings = diffKit("claims-api", "3-channel-and-notes-fixed.yaml", "4-approval-later.yaml");

        List<CompatibilityFinding> breaking = bySeverity(findings, Severity.BREAKING);
        assertThat(breaking).hasSize(2);
        assertThat(breaking).allSatisfy(f ->
                assertThat(f.code()).isEqualTo("RESP.REQUIRED_REMOVED"));
        assertThat(breaking).allSatisfy(f -> assertThat(f.fieldPath()).isEqualTo("amountApproved"));

        List<CompatibilityFinding> added202 = byCode(findings, "RESP.RESPONSE_ADDED");
        assertThat(added202).hasSize(1);
        assertThat(added202.get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(added202.get(0).method()).isEqualTo("POST");
    }

    @Test
    @DisplayName("claims 1.0.0 → 1.1.0: additive only — channel and assessorNotes, nothing breaking")
    void claims11IsAdditive() {
        List<CompatibilityFinding> findings = diffKit("claims-api", "1-initial.yaml", "3-channel-and-notes-fixed.yaml");
        assertThat(bySeverity(findings, Severity.BREAKING)).isEmpty();
        assertThat(byCode(findings, "REQ.PROPERTY_ADDED")).hasSize(1);
        assertThat(byCode(findings, "REQ.PROPERTY_ADDED").get(0).fieldPath()).isEqualTo("channel");
        // assessorNotes is added on both Claim-bearing responses (POST 201 and GET 200)
        assertThat(byCode(findings, "RESP.PROPERTY_ADDED")).hasSize(2);
        assertThat(byCode(findings, "RESP.PROPERTY_ADDED"))
                .allSatisfy(f -> assertThat(f.fieldPath()).isEqualTo("assessorNotes"));
    }

    @Test
    @DisplayName("quote 2.0.0 → 2.1.0: purely additive (ncbPercent, taxes)")
    void quote21IsAdditive() {
        List<CompatibilityFinding> findings = diffKit("quote-api", "3-price-lines.yaml", "4-ncb-and-taxes.yaml");
        assertThat(bySeverity(findings, Severity.BREAKING)).isEmpty();
        assertThat(byCode(findings, "REQ.PROPERTY_ADDED").get(0).fieldPath()).isEqualTo("rider.ncbPercent");
        assertThat(byCode(findings, "RESP.PROPERTY_ADDED").get(0).fieldPath()).isEqualTo("premium.taxes");
    }

    private static List<CompatibilityFinding> bySeverity(List<CompatibilityFinding> findings, Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).toList();
    }

    private static List<CompatibilityFinding> byCode(List<CompatibilityFinding> findings, String code) {
        return findings.stream().filter(f -> f.code().equals(code)).toList();
    }
}
