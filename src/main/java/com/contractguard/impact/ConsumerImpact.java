package com.contractguard.impact;

import java.util.List;

/**
 * Per-consumer rollup for one service version transition. {@code level} is the worst
 * FindingImpact level; {@code operations} lists the consumer's declared bindings against
 * the analyzed service (display form); {@code reason} explains a level when the finding
 * list alone is not self-explanatory (e.g. no declared usage of the service at all).
 */
public record ConsumerImpact(String consumer, String team, ImpactLevel level,
                             List<String> operations, List<FindingImpact> impacts,
                             String reason) {

    public ConsumerImpact {
        operations = List.copyOf(operations);
        impacts = List.copyOf(impacts);
    }
}
