package com.contractguard.domain;

import java.util.List;

/** The gate decision plus the action required to proceed, with human-readable reasons. */
public record DecisionResult(Decision decision, String requiredAction, List<String> reasons) {

    public DecisionResult {
        reasons = List.copyOf(reasons);
    }
}
