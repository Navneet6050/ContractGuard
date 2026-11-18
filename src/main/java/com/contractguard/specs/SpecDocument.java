package com.contractguard.specs;

import io.swagger.v3.oas.models.OpenAPI;

import java.util.List;
import java.util.Map;

/**
 * A successfully parsed and validated specification. Holds the canonical form (for
 * duplicate detection), the effective wire-contract map (for compatibility analysis)
 * and the deprecation scan. The typed swagger-core model is carried for later pipeline
 * stages but is intentionally not serialized.
 */
public final class SpecDocument {

    private final String openapiVersion;
    private final String title;
    private final String specVersion;
    private final String canonicalJson;
    private final Map<String, String> effectiveSchemas;
    private final Map<String, String> pathAliases;
    private final List<DeprecationRecord> deprecations;
    private final transient OpenAPI model;

    public SpecDocument(String openapiVersion, String title, String specVersion,
                        String canonicalJson, Map<String, String> effectiveSchemas,
                        Map<String, String> pathAliases,
                        List<DeprecationRecord> deprecations, OpenAPI model) {
        this.openapiVersion = openapiVersion;
        this.title = title;
        this.specVersion = specVersion;
        this.canonicalJson = canonicalJson;
        this.effectiveSchemas = Map.copyOf(effectiveSchemas);
        this.pathAliases = Map.copyOf(pathAliases);
        this.deprecations = List.copyOf(deprecations);
        this.model = model;
    }

    public String getOpenapiVersion() { return openapiVersion; }
    public String getTitle() { return title; }
    public String getSpecVersion() { return specVersion; }
    public String getCanonicalJson() { return canonicalJson; }
    public Map<String, String> getEffectiveSchemas() { return effectiveSchemas; }
    /** pathShape → raw path template (e.g. "/quotes/{0}" → "/quotes/{quoteId}"). */
    public Map<String, String> getPathAliases() { return pathAliases; }
    public List<DeprecationRecord> getDeprecations() { return deprecations; }
    public OpenAPI getModel() { return model; }
}
