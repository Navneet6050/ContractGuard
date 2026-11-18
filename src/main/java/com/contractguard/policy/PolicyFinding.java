package com.contractguard.policy;

import com.contractguard.compatibility.Severity;

/**
 * One version-policy finding. This channel is SEPARATE from compatibility findings:
 * policy findings decorate the attempt (display + audit) but never gate publication
 * by themselves — gating is driven exclusively by compatibility findings and consumer
 * impact (PLAN.md, DECISIONS.md A-02).
 */
public record PolicyFinding(String code, Severity severity, String detail) {
}
