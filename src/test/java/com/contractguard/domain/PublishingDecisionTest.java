package com.contractguard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.contractguard.compatibility.CompatibilityFinding;
import com.contractguard.compatibility.Severity;
import com.contractguard.impact.FindingImpact;
import com.contractguard.impact.ImpactLevel;
import com.contractguard.impact.ImpactSummary;
import com.contractguard.impact.ConsumerImpact;

/**
 * The publishing gate rules (PLAN.md §7, DECISIONS.md A-01/A-09), in isolation:
 * no breaking findings → publish; breaking + zero AFFECTED consumers → pending
 * acknowledgment; breaking + ≥1 AFFECTED → block (override with justification).
 * Version-policy findings are not an input to this class at all (rule 4/A-02):
 * invalid specs are rejected earlier, unconditionally.
 */
class PublishingDecisionTest {

    private static CompatibilityFinding breaking(String code) {
        return new CompatibilityFinding(code, Severity.BREAKING, "requestBody", "POST",
                "/quotes", "POST /quotes :: requestBody", "vehicle.region",
                "required property 'region' added", null, null);
    }

    private static CompatibilityFinding warn() {
        return new CompatibilityFinding("DEPRECATION.ADDED", Severity.WARN, "LIFECYCLE",
                null, null, "Policy.legacyCode", "Policy.legacyCode",
                "field newly marked deprecated", null, null);
    }

    private static ImpactSummary summary(ImpactLevel worst) {
        List<FindingImpact> impacts = List.of(new FindingImpact("REQ.REQUIRED_ADDED",
                "breaking", "POST", "/quotes", "POST /quotes :: requestBody",
                "vehicle.region", worst, "consumer declares usage"));
        List<ConsumerImpact> consumers = List.of(new ConsumerImpact("mobile-app", "Mobile",
                worst, List.of("POST /quotes"), impacts, "declares usage of the changed operation"));
        int affected = worst == ImpactLevel.AFFECTED ? 1 : 0;
        int possible = worst == ImpactLevel.POSSIBLE ? 1 : 0;
        return new ImpactSummary("quote-api", "1.0.0", "2.0.0", consumers,
                affected, possible, 4 - affected - possible);
    }

    @Test
    @DisplayName("rule 1: no findings at all (first publication) -> PUBLISH")
    void publishWithNoFindings() {
        DecisionResult result = PublishingDecision.evaluate(List.of(), summary(ImpactLevel.NONE));
        assertThat(result.decision()).isEqualTo(Decision.PUBLISH);
        assertThat(result.requiredAction()).contains("publication permitted");
    }

    @Test
    @DisplayName("rule 1: only warn/info findings -> PUBLISH (non-gating)")
    void publishWithNonGatingFindings() {
        DecisionResult result = PublishingDecision.evaluate(List.of(warn()), summary(ImpactLevel.NONE));
        assertThat(result.decision()).isEqualTo(Decision.PUBLISH);
        assertThat(result.reasons().get(0)).contains("non-gating");
    }

    @Test
    @DisplayName("rule 2: breaking findings, zero AFFECTED consumers -> PENDING_ACK")
    void pendingAckWithoutAffectedConsumers() {
        DecisionResult result = PublishingDecision.evaluate(List.of(breaking("REQ.REQUIRED_ADDED")),
                summary(ImpactLevel.NONE));
        assertThat(result.decision()).isEqualTo(Decision.PENDING_ACK);
        assertThat(result.requiredAction()).contains("acknowledge");
        assertThat(result.reasons().get(0)).contains("affected=0");
    }

    @Test
    @DisplayName("rule 2: breaking findings with only POSSIBLE consumers -> PENDING_ACK (advisory tier never gates)")
    void pendingAckWithOnlyPossibleConsumers() {
        DecisionResult result = PublishingDecision.evaluate(List.of(breaking("REQ.REQUIRED_ADDED")),
                summary(ImpactLevel.POSSIBLE));
        assertThat(result.decision()).isEqualTo(Decision.PENDING_ACK);
    }

    @Test
    @DisplayName("rule 3: breaking findings with an AFFECTED consumer -> BLOCK")
    void blockWithAffectedConsumers() {
        DecisionResult result = PublishingDecision.evaluate(List.of(breaking("REQ.REQUIRED_ADDED")),
                summary(ImpactLevel.AFFECTED));
        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.requiredAction()).contains("override");
        assertThat(result.reasons().get(0)).contains("mobile-app");
    }

    @Test
    @DisplayName("blocking is driven by ANY breaking finding, not only the first")
    void blockCountsEveryBreakingFinding() {
        DecisionResult result = PublishingDecision.evaluate(
                List.of(warn(), breaking("RESP.PROPERTY_REMOVED"), breaking("REQ.REQUIRED_ADDED")),
                summary(ImpactLevel.AFFECTED));
        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.reasons().get(0)).startsWith("2 breaking finding(s)");
    }
}
