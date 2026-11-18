package com.contractguard.impact;

/**
 * One consumer × one compatibility finding result. Carries the underlying finding's
 * identity (code/severity/location/fieldPath), the impact tier and a concise
 * human-readable reason. The reason never hides uncertainty: unknown dependence is
 * POSSIBLE with an explicit explanation, never a silent NONE.
 */
public record FindingImpact(String findingCode, String findingSeverity,
                            String method, String path, String location, String fieldPath,
                            ImpactLevel level, String reason) {
}
