package com.contractguard.impact;

/**
 * Consumer impact tier (PLAN.md §7). AFFECTED gates publishing, POSSIBLE warns,
 * NONE clears — the policy module maps these to gate decisions in a later milestone.
 */
public enum ImpactLevel {
    AFFECTED(2),
    POSSIBLE(1),
    NONE(0);

    private final int rank;

    ImpactLevel(int rank) {
        this.rank = rank;
    }

    /** Higher = worse; used to fold per-finding impacts into a consumer-level level. */
    public int rank() {
        return rank;
    }

    /** The worse of two levels. */
    public static ImpactLevel worst(ImpactLevel a, ImpactLevel b) {
        return a.rank >= b.rank ? a : b;
    }
}
