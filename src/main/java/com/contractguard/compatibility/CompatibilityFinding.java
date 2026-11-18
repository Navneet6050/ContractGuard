package com.contractguard.compatibility;

/**
 * One classified contract difference. Field layout mirrors the findings table in
 * schema.sql. {@code oldJson}/{@code newJson} carry the relevant canonical schema
 * fragment (or null) as evidence; {@code location} is the effective location key
 * ("VERB /shape :: scope"); {@code fieldPath} uses the consumer-file coordinate
 * system (dots for nesting, "[]" for array items).
 */
public record CompatibilityFinding(String code, Severity severity, String scope,
                                   String method, String path, String location,
                                   String fieldPath, String detail,
                                   String oldJson, String newJson) {
}
