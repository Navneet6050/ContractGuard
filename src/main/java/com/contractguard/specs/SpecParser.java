package com.contractguard.specs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Spec ingestion service: YAML → Jackson tree → collect-all structural validation →
 * canonical form → effective wire-contract schemas → deprecation scan.
 *
 * Swagger Parser's typed model is consulted only as an auxiliary signal; the
 * authoritative validation is the collect-all walk in {@link StructuralValidator},
 * which reports every error (not only the first) before the caller decides.
 */
@Service
public class SpecParser {

    private final YAMLMapper yamlMapper = YAMLMapper.builder().build();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * Parses and validates a specification document. Throws {@link SpecParseException}
     * carrying ALL collected errors when anything is invalid.
     */
    public SpecDocument parse(String yamlContent) {
        JsonNode root = readYaml(yamlContent);

        List<SpecValidationError> errors = new StructuralValidator(root).validate();
        if (!errors.isEmpty()) {
            throw new SpecParseException(errors);
        }

        EffectiveSchemaExtractor extractor = new EffectiveSchemaExtractor(root);
        Map<String, String> effective = extractor.extract();
        String canonicalJson = buildCanonicalJson(root);
        List<DeprecationRecord> deprecations = new DeprecationScanner(root).scan();

        return new SpecDocument(
                root.get("openapi").textValue(),
                root.get("info").get("title").textValue(),
                root.get("info").get("version").textValue(),
                canonicalJson,
                effective,
                extractor.getPathAliases(),
                deprecations,
                null);
    }

    private JsonNode readYaml(String content) {
        if (content == null || content.isBlank()) {
            throw new SpecParseException(List.of(
                    new SpecValidationError("/", "document is empty")));
        }
        try {
            JsonNode node = yamlMapper.readTree(content);
            if (node == null || node.isNull() || !node.isObject()) {
                throw new SpecParseException(List.of(
                        new SpecValidationError("/", "document is not a YAML mapping (object)")));
            }
            return node;
        } catch (SpecParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SpecParseException(List.of(new SpecValidationError("/",
                    "invalid YAML: " + e.getMessage())));
        }
    }

    /**
     * Whole-document canonical form with sorted keys and sorted {@code required}
     * arrays — insensitive to formatting and key order, sensitive to real content.
     * Used for duplicate-version identity comparison.
     */
    private String buildCanonicalJson(JsonNode root) {
        Object canonical = CanonicalJson.canonicalize(root);
        return jsonMapper.writeValueAsString(canonical);
    }

    /** Renders an effective-schema map as a stable JSON object (sorted keys). */
    public String writeEffectiveJson(Map<String, String> effective) {
        TreeMap<String, String> sorted = new TreeMap<>(effective);
        return jsonMapper.writeValueAsString(sorted);
    }

    /** Small helper reused by pipeline stages that need ordered DTO maps. */
    public Map<String, Object> orderedInfo(SpecDocument doc) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("openapi", doc.getOpenapiVersion());
        info.put("title", doc.getTitle());
        info.put("version", doc.getSpecVersion());
        info.put("effectiveLocations", doc.getEffectiveSchemas().size());
        info.put("deprecations", doc.getDeprecations().size());
        return info;
    }
}
