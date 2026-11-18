package com.contractguard.domain;

/**
 * The publishing-gate outcomes (PLAN.md §8). PUBLISH publishes immediately;
 * PENDING_ACK parks the attempt until an explicit acknowledgment; BLOCK parks the
 * attempt until an explicit override with a non-empty justification. Every decision
 * is an explicit human action — acknowledgment and override are separate endpoints,
 * never automatic.
 */
public enum Decision {
    PUBLISH, PENDING_ACK, BLOCK
}
