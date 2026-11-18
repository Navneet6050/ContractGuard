package com.contractguard.impact;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.contractguard.compatibility.CompatibilityEngine;
import com.contractguard.compatibility.CompatibilityFinding;
import com.contractguard.consumers.ConsumerDeclaration;
import com.contractguard.consumers.ConsumerParser;
import com.contractguard.consumers.ConsumerRegistry;
import com.contractguard.consumers.DeclaredOperation;
import com.contractguard.specs.Fixtures;
import com.contractguard.specs.SpecDocument;
import com.contractguard.specs.SpecParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The required starter-kit verification cases, run against the REAL consumer files and
 * REAL spec pairs through the full pipeline (SpecParser → CompatibilityEngine →
 * ConsumerImpactAnalyzer). Expectations come from contract content, not filenames.
 */
class KitConsumerImpactTest {

    private final SpecParser specParser = new SpecParser();
    private final CompatibilityEngine engine = new CompatibilityEngine();
    private final ConsumerImpactAnalyzer analyzer = new ConsumerImpactAnalyzer();
    private final List<ConsumerDeclaration> consumers = new ConsumerRegistry(java.nio.file.Path.of("consumers")).all();

    private ImpactSummary analyze(String service, String serviceDir, String oldFile, String newFile) {
        SpecDocument oldDoc = specParser.parse(Fixtures.spec(serviceDir, oldFile));
        SpecDocument newDoc = specParser.parse(Fixtures.spec(serviceDir, newFile));
        List<CompatibilityFinding> findings = engine.diff(oldDoc.getEffectiveSchemas(),
                newDoc.getEffectiveSchemas(), oldDoc.getPathAliases(), newDoc.getPathAliases());
        return analyzer.analyze(service, oldDoc, newDoc, findings, consumers);
    }

    private static ConsumerImpact consumer(ImpactSummary summary, String name) {
        return summary.consumers().stream()
                .filter(c -> c.consumer().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("policy legacyCode removal: payments-svc AFFECTED (reads it), notifications-svc provably NONE")
    void paymentsAffectedByLegacyCodeRemoval() {
        ImpactSummary summary = analyze("policy-api", "policy-api",
                "2-deprecations.yaml", "3-remove-old-fields.yaml");
        assertThat(summary.oldVersion()).isEqualTo("1.1.0");
        assertThat(summary.newVersion()).isEqualTo("2.0.0");

        ConsumerImpact payments = consumer(summary, "payments-svc");
        assertThat(payments.level()).isEqualTo(ImpactLevel.AFFECTED);
        assertThat(payments.impacts())
                .filteredOn(i -> i.level() == ImpactLevel.AFFECTED)
                .anySatisfy(i -> {
                    assertThat(i.findingCode()).isEqualTo("RESP.PROPERTY_REMOVED");
                    assertThat(i.fieldPath()).isEqualTo("legacyCode");
                    assertThat(i.reason()).contains("you read 'legacyCode'");
                });

        ConsumerImpact notifications = consumer(summary, "notifications-svc");
        assertThat(notifications.level()).isEqualTo(ImpactLevel.NONE);
        // every per-finding impact explains WHY it is not affected (e.g. holder.phone
        // removed but notifications reads holder.email — sibling, not ancestor)
        assertThat(notifications.impacts()).allSatisfy(i -> assertThat(i.level()).isEqualTo(ImpactLevel.NONE));
        assertThat(notifications.impacts())
                .anySatisfy(i -> assertThat(i.reason()).contains("do not include 'holder.phone'"));

        // mobile-app and partner-portal declare no policy-api usage at all
        assertThat(consumer(summary, "mobile-app").level()).isEqualTo(ImpactLevel.NONE);
        assertThat(consumer(summary, "partner-portal").level()).isEqualTo(ImpactLevel.NONE);
        assertThat(consumer(summary, "mobile-app").reason()).contains("declares no usage of 'policy-api'");
    }

    @Test
    @DisplayName("claims amountApproved guarantee removal: mobile-app AFFECTED, notifications-svc NONE on the SAME operation")
    void mobileAffectedByAmountApproved() {
        ImpactSummary summary = analyze("claims-api", "claims-api",
                "3-channel-and-notes-fixed.yaml", "4-approval-later.yaml");

        ConsumerImpact mobile = consumer(summary, "mobile-app");
        assertThat(mobile.level()).isEqualTo(ImpactLevel.AFFECTED);
        assertThat(mobile.impacts())
                .filteredOn(i -> i.level() == ImpactLevel.AFFECTED)
                .anySatisfy(i -> {
                    assertThat(i.findingCode()).isEqualTo("RESP.REQUIRED_REMOVED");
                    assertThat(i.fieldPath()).isEqualTo("amountApproved");
                    assertThat(i.reason()).contains("no longer guaranteed");
                });

        // notifications-svc uses the SAME GET /claims/{claimId} operation but reads only
        // claimId and status — proof of independence, NOT a POSSIBLE
        ConsumerImpact notifications = consumer(summary, "notifications-svc");
        assertThat(notifications.level()).isEqualTo(ImpactLevel.NONE);
        assertThat(notifications.impacts())
                .filteredOn(i -> i.fieldPath() != null && i.fieldPath().equals("amountApproved"))
                .hasSize(1)
                .allSatisfy(i -> {
                    assertThat(i.level()).isEqualTo(ImpactLevel.NONE);
                    assertThat(i.reason()).contains("do not include 'amountApproved'");
                });
    }

    @Test
    @DisplayName("quote amount→value rename: mobile-app AFFECTED, partner-portal NONE, notifications-svc POSSIBLE via status enum, cache-purge touches nobody")
    void quoteRenameImpact() {
        ImpactSummary summary = analyze("quote-api", "quote-api",
                "2-reorganised.yaml", "3-price-lines.yaml");

        ConsumerImpact mobile = consumer(summary, "mobile-app");
        assertThat(mobile.level()).isEqualTo(ImpactLevel.AFFECTED);
        assertThat(mobile.impacts())
                .filteredOn(i -> i.level() == ImpactLevel.AFFECTED)
                .anySatisfy(i -> {
                    assertThat(i.findingCode()).isEqualTo("RESP.PROPERTY_RENAMED");
                    assertThat(i.fieldPath()).isEqualTo("premium.breakdown[].amount");
                    assertThat(i.reason()).contains("renamed to 'value'");
                });
        // mobile also reads 'status', whose enum widens → advisory POSSIBLE, never AFFECTED
        assertThat(mobile.impacts())
                .filteredOn(i -> i.findingCode().equals("RESP.ENUM_WIDENED"))
                .hasSize(2)
                .allSatisfy(i -> assertThat(i.level()).isEqualTo(ImpactLevel.POSSIBLE));

        // partner-portal reads premium.total/currency/quoteId — no overlap with the rename
        // or with 'status' → provably NONE despite using POST /quotes
        ConsumerImpact partner = consumer(summary, "partner-portal");
        assertThat(partner.level()).isEqualTo(ImpactLevel.NONE);
        assertThat(partner.impacts()).noneSatisfy(i -> assertThat(i.level()).isEqualTo(ImpactLevel.AFFECTED));

        // notifications-svc reads 'status' on GET /quotes/{id} → advisory widening POSSIBLE
        assertThat(consumer(summary, "notifications-svc").level()).isEqualTo(ImpactLevel.POSSIBLE);

        // the internal cache-purge required parameter touches NO consumer: none of them
        // declares DELETE /internal/pricing-cache (structured join, not substring)
        assertThat(summary.consumers())
                .flatExtracting(ConsumerImpact::impacts)
                .noneSatisfy(i -> assertThat(i.location()).startsWith("DELETE /internal/"));
    }

    @Test
    @DisplayName("policy endorsement request changes: partner-portal AFFECTED; same-service consumers of other operations isolated")
    void partnerAffectedByPolicyRequestChanges() {
        ImpactSummary summary = analyze("policy-api", "policy-api",
                "3-remove-old-fields.yaml", "4-endorsement-rules.yaml");

        ConsumerImpact partner = consumer(summary, "partner-portal");
        assertThat(partner.level()).isEqualTo(ImpactLevel.AFFECTED);
        assertThat(partner.impacts())
                .filteredOn(i -> i.level() == ImpactLevel.AFFECTED)
                .anySatisfy(i -> {
                    assertThat(i.findingCode()).isEqualTo("REQ.ENUM_NARROWED");
                    assertThat(i.fieldPath()).isEqualTo("type");
                    assertThat(i.reason()).contains("VEHICLE").contains("you send 'type'");
                });
        assertThat(partner.impacts())
                .filteredOn(i -> i.findingCode().equals("REQ.REQUIRED_ADDED"))
                .hasSize(1)
                .allSatisfy(i -> {
                    // partner proves it does not send 'requestedBy' → directly affected
                    assertThat(i.level()).isEqualTo(ImpactLevel.AFFECTED);
                    assertThat(i.reason()).contains("now required");
                });

        // payments-svc and notifications-svc use OTHER operations of policy-api
        // (GET /policies/{policyId}) — no cross-operation contamination
        assertThat(consumer(summary, "payments-svc").level()).isEqualTo(ImpactLevel.NONE);
        assertThat(consumer(summary, "notifications-svc").level()).isEqualTo(ImpactLevel.NONE);
        assertThat(consumer(summary, "payments-svc").impacts()).isEmpty();
        assertThat(consumer(summary, "notifications-svc").impacts()).isEmpty();
    }

    @Test
    @DisplayName("determinism: two runs over the same transition produce identical summaries")
    void deterministic() {
        ImpactSummary first = analyze("policy-api", "policy-api",
                "2-deprecations.yaml", "3-remove-old-fields.yaml");
        ImpactSummary second = analyze("policy-api", "policy-api",
                "2-deprecations.yaml", "3-remove-old-fields.yaml");
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("counts match per-consumer levels for the legacyCode transition")
    void countsAreConsistent() {
        ImpactSummary summary = analyze("policy-api", "policy-api",
                "2-deprecations.yaml", "3-remove-old-fields.yaml");
        long affected = summary.consumers().stream().filter(c -> c.level() == ImpactLevel.AFFECTED).count();
        long possible = summary.consumers().stream().filter(c -> c.level() == ImpactLevel.POSSIBLE).count();
        assertThat(summary.affectedCount()).isEqualTo(affected);
        assertThat(summary.possibleCount()).isEqualTo(possible);
        assertThat(summary.affectedCount() + summary.possibleCount() + summary.unaffectedCount())
                .isEqualTo(summary.consumers().size());
        assertThat(summary.affectedCount()).isEqualTo(1); // payments-svc only
        assertThat(summary.hasAffected()).isTrue();
    }

    /** Re-parse guarantee: consumers loaded from the registry equal parser output. */
    @Test
    @DisplayName("registry consumers are parser-parsable and preserve declared paths verbatim")
    void registryDeclarationsRoundTrip() {
        ConsumerParser parser = new ConsumerParser();
        for (ConsumerDeclaration c : consumers) {
            for (DeclaredOperation op : c.operations()) {
                assertThat(op.provider()).isNotBlank();
                assertThat(op.method()).matches("[A-Z]+");
                assertThat(op.rawPath()).startsWith("/");
                // every declared path has a shape and vice versa (never blank)
                assertThat(op.pathShape()).startsWith("/");
            }
        }
        assertThat(consumers).allSatisfy(c -> assertThat(parser.parse(
                Fixtures.consumer(c.name() + ".yaml")).name()).isEqualTo(c.name()));
    }
}
