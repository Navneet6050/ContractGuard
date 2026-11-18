package com.contractguard.impact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.contractguard.compatibility.CompatibilityFinding;
import com.contractguard.compatibility.Severity;
import com.contractguard.consumers.ConsumerDeclaration;
import com.contractguard.consumers.ConsumerParser;
import com.contractguard.specs.Fixtures;
import com.contractguard.specs.SpecDocument;
import com.contractguard.specs.SpecParser;

/**
 * The cumulative consumer-drift scan (DECISIONS.md A-08 default): declared consumer
 * usage that no longer exists in the incoming spec is reported as a non-gating warn,
 * regardless of WHICH published version removed it. Grounded in the real kit specs and
 * consumer files.
 */
class ConsumerDriftScannerTest {

    private final SpecParser parser = new SpecParser();
    private final ConsumerParser consumerParser = new ConsumerParser();
    private final ConsumerDriftScanner scanner = new ConsumerDriftScanner();

    private SpecDocument spec(String service, String file) {
        return parser.parse(Fixtures.spec(service, file));
    }

    private ConsumerDeclaration consumer(String file) {
        return consumerParser.parse(Fixtures.consumer(file));
    }

    @Test
    @DisplayName("quote 1.0.0 -> 1.1.0 (cosmetic reorg): no drift for any kit consumer")
    void cosmeticReorgProducesNoDrift() {
        List<CompatibilityFinding> findings = scanner.scan("quote-api", null,
                spec("quote-api", "1-initial.yaml"),
                List.of(consumer("mobile-app.yaml"), consumer("partner-portal.yaml"),
                        consumer("notifications-svc.yaml")));
        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("after the amount->value rename published in 2.0.0, a later 2.1.0 upload still warns that mobile-app reads a field that no longer exists")
    void multiVersionDriftIsDetectedOnLaterUploads() {
        SpecDocument old = spec("quote-api", "3-price-lines.yaml"); // 2.0.0 (published)
        SpecDocument candidate = spec("quote-api", "4-ncb-and-taxes.yaml"); // 2.1.0
        List<CompatibilityFinding> delta = new com.contractguard.compatibility.CompatibilityEngine()
                .diff(old.getEffectiveSchemas(), candidate.getEffectiveSchemas(),
                        old.getPathAliases(), candidate.getPathAliases());
        List<CompatibilityFinding> findings = scanner.scan("quote-api", old, candidate,
                List.of(consumer("mobile-app.yaml")), delta);

        assertThat(findings).hasSize(1);
        CompatibilityFinding drift = findings.get(0);
        assertThat(drift.code()).isEqualTo("CONSUMER.DRIFT");
        assertThat(drift.severity()).isEqualTo(Severity.WARN); // non-gating by contract
        assertThat(drift.fieldPath()).isEqualTo("premium.breakdown[].amount");
        assertThat(drift.scope()).isEqualTo("CONSUMERS");
        assertThat(drift.detail()).contains("mobile-app").contains("premium.breakdown[].amount");
    }

    @Test
    @DisplayName("a removal happening in THIS hop is owned by the delta engine, not double-reported as drift")
    void removalInThisHopIsSuppressed() {
        SpecDocument old = spec("quote-api", "2-reorganised.yaml"); // 1.1.0: amount exists
        SpecDocument candidate = spec("quote-api", "3-price-lines.yaml"); // 2.0.0: renamed
        List<CompatibilityFinding> delta = new com.contractguard.compatibility.CompatibilityEngine()
                .diff(old.getEffectiveSchemas(), candidate.getEffectiveSchemas(),
                        old.getPathAliases(), candidate.getPathAliases());
        assertThat(delta).anyMatch(f -> f.code().equals("RESP.PROPERTY_RENAMED"));

        List<CompatibilityFinding> findings = scanner.scan("quote-api", old, candidate,
                List.of(consumer("mobile-app.yaml")), delta);
        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("drift never changes the gate decision: warn findings and POSSIBLE impacts do not block")
    void driftDoesNotGate() {
        SpecDocument old = spec("quote-api", "3-price-lines.yaml");
        SpecDocument candidate = spec("quote-api", "4-ncb-and-taxes.yaml");
        List<CompatibilityFinding> findings = new java.util.ArrayList<>(new com.contractguard.compatibility.CompatibilityEngine()
                .diff(old.getEffectiveSchemas(), candidate.getEffectiveSchemas(),
                        old.getPathAliases(), candidate.getPathAliases()));
        findings.addAll(scanner.scan("quote-api", old, candidate, List.of(consumer("mobile-app.yaml")),
                findings));
        ImpactSummary summary = new ConsumerImpactAnalyzer().analyze("quote-api", old, candidate,
                findings, List.of(consumer("mobile-app.yaml")));

        com.contractguard.domain.DecisionResult decision =
                com.contractguard.domain.PublishingDecision.evaluate(findings, summary);
        assertThat(decision.decision()).isEqualTo(com.contractguard.domain.Decision.PUBLISH);
        // the drift reached the impact analysis as a POSSIBLE advisory for mobile-app
        assertThat(summary.consumers().stream()
                .anyMatch(c -> c.consumer().equals("mobile-app")
                        && c.level() == ImpactLevel.POSSIBLE
                        && c.impacts().stream().anyMatch(i -> i.findingCode().equals("CONSUMER.DRIFT"))))
                .isTrue();
    }

    @Test
    @DisplayName("a consumer whose declared operation exists in neither spec gets a stale-binding warning")
    void staleBindingIsReported() {
        SpecDocument doc = spec("quote-api", "1-initial.yaml");
        String yaml = """
                consumer: legacy-caller
                team: Legacy
                uses:
                - provider: quote-api
                  method: DELETE
                  path: /quotes/{id}
                  sends: [id]
                  reads: []
                """;
        List<CompatibilityFinding> findings = scanner.scan("quote-api", null, doc,
                List.of(consumerParser.parse(yaml)));
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("CONSUMER.STALE_BINDING");
        assertThat(findings.get(0).detail()).contains("legacy-caller").contains("DELETE /quotes/{id}");
    }

    @Test
    @DisplayName("a removal happening in the current->candidate hop is NOT double-reported (delta engine owns it)")
    void removalInThisHopIsLeftToTheDeltaEngine() {
        // policy 1.1.0 still documents legacyCode; 2.0.0 removes it in THIS hop, and the
        // delta finding RESP.PROPERTY_REMOVED already names payments-svc as affected
        SpecDocument old = spec("policy-api", "2-deprecations.yaml");
        SpecDocument candidate = spec("policy-api", "3-remove-old-fields.yaml");
        List<CompatibilityFinding> delta = new com.contractguard.compatibility.CompatibilityEngine()
                .diff(old.getEffectiveSchemas(), candidate.getEffectiveSchemas(),
                        old.getPathAliases(), candidate.getPathAliases());
        List<CompatibilityFinding> findings = scanner.scan("policy-api", old, candidate,
                List.of(consumer("payments-svc.yaml")), delta);
        assertThat(findings).isEmpty();
    }
}
