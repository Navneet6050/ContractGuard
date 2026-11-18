package com.contractguard.impact;

import java.util.List;

/**
 * Full result of one candidate version transition: the compatibility findings and the
 * consumer impact rollup in one payload, keyed by service and the two versions.
 */
public record ImpactReport(String service, String oldVersion, String newVersion,
                           List<com.contractguard.compatibility.CompatibilityFinding> findings,
                           ImpactSummary summary) {

    public ImpactReport {
        findings = List.copyOf(findings);
    }
}
