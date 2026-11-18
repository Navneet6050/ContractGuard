package com.contractguard.domain;

import java.util.List;
import java.util.stream.Collectors;

import com.contractguard.compatibility.CompatibilityFinding;
import com.contractguard.compatibility.Severity;
import com.contractguard.impact.ImpactSummary;

/**
 * The publishing gate (PLAN.md §7 gate mapping, DECISIONS.md A-01/A-09):
 *
 * <ul>
 *   <li>no breaking compatibility finding → {@link Decision#PUBLISH} (rule 1)</li>
 *   <li>breaking findings, zero AFFECTED consumers → {@link Decision#PENDING_ACK}
 *       (rule 2; POSSIBLE-tier impacts are advisory warnings, not gates)</li>
 *   <li>≥1 breaking finding with ≥1 AFFECTED consumer → {@link Decision#BLOCK}
 *       (rule 3; publishable only via explicit override with justification)</li>
 * </ul>
 *
 * Version-policy (semver) findings are deliberately NOT an input: they decorate the
 * attempt but can never change the decision (A-02). Invalid specifications never
 * reach this class - they are rejected at parse time, unconditionally (rule 4).
 */
public final class PublishingDecision {

    private PublishingDecision() {
    }

    /**
     * @param findings compatibility findings only (may include non-gating warn/info
     *                 lifecycle findings); version-policy findings must not be included
     */
    public static DecisionResult evaluate(List<CompatibilityFinding> findings, ImpactSummary summary) {
        List<CompatibilityFinding> breaking = findings.stream()
                .filter(f -> f.severity() == Severity.BREAKING)
                .toList();

        if (breaking.isEmpty()) {
            String reason = "no breaking compatibility findings ("
                    + findings.size() + " non-gating warn/info finding(s)) - publication permitted";
            return new DecisionResult(Decision.PUBLISH, "none: publication permitted", List.of(reason));
        }

        if (summary.hasAffected()) {
            String affectedNames = summary.consumers().stream()
                    .filter(c -> c.level() == com.contractguard.impact.ImpactLevel.AFFECTED)
                    .map(c -> c.consumer())
                    .collect(Collectors.joining(", "));
            List<String> reasons = new java.util.ArrayList<>();
            reasons.add(breaking.size() + " breaking finding(s) directly affect "
                    + summary.affectedCount() + " registered consumer(s): " + affectedNames);
            reasons.add("publication is blocked by default; an explicit override with a "
                    + "non-empty justification is required");
            return new DecisionResult(Decision.BLOCK,
                    "override with justification required (POST /api/attempts/{id}/override)",
                    reasons);
        }

        long possible = summary.consumers().stream()
                .filter(c -> c.level() == com.contractguard.impact.ImpactLevel.POSSIBLE)
                .count();
        List<String> reasons = new java.util.ArrayList<>();
        reasons.add(breaking.size() + " breaking finding(s) but no registered consumer is "
                + "provably affected (affected=0, possible=" + possible + ")");
        reasons.add("explicit acknowledgment is required before publication");
        return new DecisionResult(Decision.PENDING_ACK,
                "acknowledge required (POST /api/attempts/{id}/acknowledge)", reasons);
    }
}
