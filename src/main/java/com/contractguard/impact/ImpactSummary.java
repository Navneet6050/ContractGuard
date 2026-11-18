package com.contractguard.impact;

import java.util.List;

/**
 * Aggregate impact for one candidate service version transition: every registered
 * consumer classified against the compatibility findings. Deterministic (consumers
 * sorted by name, impacts in finding order) and directly consumable by the UI and
 * the publishing-policy module.
 */
public record ImpactSummary(String service, String oldVersion, String newVersion,
                            List<ConsumerImpact> consumers,
                            int affectedCount, int possibleCount, int unaffectedCount) {

    public ImpactSummary {
        consumers = List.copyOf(consumers);
    }

    /** True when at least one consumer is AFFECTED (the policy gate condition). */
    public boolean hasAffected() {
        return affectedCount > 0;
    }
}
