package com.contractguard.specs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Effective-schema extraction: for every wire location (method + path shape + scope)
 * produces a $ref-free, inline-expanded schema rendered as canonical JSON. Component
 * names and $ref placement disappear here, so quote v1.0.0 (named components, $refs)
 * and v1.1.0 (inlined objects, renamed components) produce IDENTICAL maps when their
 * wire contract is the same.
 *
 * Scopes: "requestBody:<mediaType>", "param:<in>:<ordinal>" (one entry per parameter
 * position; OAS override rules honored; the value is an envelope
 * {"required": bool, "schema": <expanded schema|null>} — the parameter NAME is
 * deliberately excluded so that renaming a parameter does not change the wire shape),
 * "response:<status>[:<mediaType>]" (a documented status without a schema is recorded
 * as the literal "null").
 *
 * Also collects a pathShape → rawPath alias map (first raw path wins) so findings can
 * display the human path while matching stays shape-based, and a locationKey → parameter
 * NAME index (the envelopes themselves stay name-free) so downstream impact analysis can
 * match consumer-declared parameter names against {@code param:<in>:<ordinal>} findings.
 */
final class EffectiveSchemaExtractor {

    private static final int MAX_EXPANSION_DEPTH = 50;

    private final JsonNode root;
    private final Map<String, String> pathAliases = new TreeMap<>();
    private final Map<String, String> paramNames = new TreeMap<>();

    EffectiveSchemaExtractor(JsonNode root) {
        this.root = root;
    }

    Map<String, String> extract() {
        Map<String, String> out = new TreeMap<>();
        JsonNode paths = root.get("paths");
        if (paths == null || !paths.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
            String rawPath = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();
            if (pathItem == null || !pathItem.isObject()) {
                continue;
            }
            String pathShape = PathShapes.normalize(rawPath);
            pathAliases.putIfAbsent(pathShape, rawPath);
            // path-level parameters apply to every operation unless overridden
            List<JsonNode> pathLevelParams = parameterList(pathItem.get("parameters"));

            for (Map.Entry<String, JsonNode> opEntry : pathItem.properties()) {
                String method = opEntry.getKey();
                if (!HttpMethods.isMethod(method)) {
                    continue; // skips non-operation keys ("parameters", "summary", ...)
                }
                JsonNode operation = opEntry.getValue();
                if (operation == null || !operation.isObject()) {
                    continue;
                }
                extractOperation(rawPath, pathShape, method, pathLevelParams, operation, out);
            }
        }
        return out;
    }

    private void extractOperation(String rawPath, String pathShape, String method,
                                  List<JsonNode> pathLevelParams, JsonNode operation,
                                  Map<String, String> out) {
        // HTTP verbs are canonicalized to uppercase (YAML gives lowercase keys; consumer
        // files and findings both use uppercase)
        String verb = method.toUpperCase(java.util.Locale.ROOT);
        // --- parameters ---
        List<JsonNode> opParams = parameterList(operation.get("parameters"));
        List<JsonNode> merged = mergeParameters(pathLevelParams, opParams);
        Map<String, Integer> counters = new TreeMap<>();
        for (JsonNode param : merged) {
            String in = text(param, "in");
            if (in == null) {
                continue;
            }
            if ("path".equals(in) && text(param, "name") != null
                    && !rawPath.contains("{" + text(param, "name") + "}")) {
                continue; // never emit params for placeholders the template does not have
            }
            String scope = "param:" + in + ":" + counters.merge(in, 1, Integer::sum);
            String locationKey = new EffectiveLocation(verb, pathShape, scope).key();
            String paramName = text(param, "name");
            if (paramName != null) {
                paramNames.put(locationKey, paramName);
            }
            JsonNode schema = param.get("schema");
            ObjectNode envelope = JsonNodeFactory.instance.objectNode();
            envelope.put("required", requiredFlag(param));
            envelope.set("schema", schema != null ? expandNode(schema, 0) : JsonNodeFactory.instance.nullNode());
            out.put(locationKey,
                    JsonMappers.writeCanonical(CanonicalJson.canonicalize(envelope)));
        }

        // --- request body ---
        JsonNode requestBody = operation.get("requestBody");
        if (requestBody != null && requestBody.isObject()) {
            JsonNode content = requestBody.get("content");
            if (content != null && content.isObject()) {
                for (Map.Entry<String, JsonNode> mediaType : content.properties()) {
                    JsonNode schema = mediaType.getValue() == null ? null : mediaType.getValue().get("schema");
                    String scope = "requestBody" + (mediaType.getKey() == null ? "" : ":" + mediaType.getKey());
                    out.put(new EffectiveLocation(verb, pathShape, scope).key(),
                            schema == null ? "null" : expand(schema));
                }
            }
        }

        // --- responses ---
        JsonNode responses = operation.get("responses");
        if (responses != null && responses.isObject()) {
            for (Map.Entry<String, JsonNode> resp : responses.properties()) {
                String status = resp.getKey();
                JsonNode response = resp.getValue();
                if (response == null || !response.isObject()) {
                    continue;
                }
                JsonNode content = response.get("content");
                if (content != null && content.isObject()) {
                    for (Map.Entry<String, JsonNode> mediaType : content.properties()) {
                        JsonNode schema = mediaType.getValue() == null ? null : mediaType.getValue().get("schema");
                        String scope = "response:" + status + (mediaType.getKey() == null ? "" : ":" + mediaType.getKey());
                        out.put(new EffectiveLocation(verb, pathShape, scope).key(),
                                schema == null ? "null" : expand(schema));
                    }
                } else {
                    // documented status without a schema is still part of the wire contract
                    out.put(new EffectiveLocation(verb, pathShape, "response:" + status).key(), "null");
                }
            }
        }
    }

    private List<JsonNode> parameterList(JsonNode parameters) {
        List<JsonNode> list = new ArrayList<>();
        if (parameters != null && parameters.isArray()) {
            for (JsonNode element : parameters) {
                if (element != null && element.isObject()) {
                    list.add(element);
                }
            }
        }
        return list;
    }

    /**
     * OAS rule: an operation-level parameter with the same (in, name) pair overrides a
     * path-level one; others are additive.
     */
    private List<JsonNode> mergeParameters(List<JsonNode> pathLevel, List<JsonNode> opLevel) {
        List<JsonNode> merged = new ArrayList<>(opLevel);
        for (JsonNode pathParam : pathLevel) {
            String in = text(pathParam, "in");
            String name = text(pathParam, "name");
            boolean overridden = false;
            for (JsonNode opParam : opLevel) {
                if (in != null && in.equals(text(opParam, "in")) && name != null
                        && name.equals(text(opParam, "name"))) {
                    overridden = true;
                    break;
                }
            }
            if (!overridden) {
                merged.add(pathParam);
            }
        }
        return merged;
    }

    /**
     * Inline-expands every local $ref (recursively, depth-guarded) and renders the
     * result as canonical JSON (sorted keys, sorted required arrays).
     */
    String expand(JsonNode schema) {
        JsonNode expanded = expandNode(schema, 0);
        Object canonical = CanonicalJson.canonicalize(expanded);
        return JsonMappers.writeCanonical(canonical);
    }

    private JsonNode expandNode(JsonNode node, int depth) {
        if (node == null || node.isMissingNode()) {
            return JsonMappers.missingNode();
        }
        if (depth > MAX_EXPANSION_DEPTH) {
            return node; // depth guard: kit has no cycles; over-deep docs pass through
        }
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isString() && RefResolver.isLocalRef(ref.stringValue())) {
                java.util.Optional<JsonNode> target = RefResolver.resolve(root, ref.stringValue());
                if (target.isEmpty()) {
                    // validator will have rejected the document; emit a marker
                    return JsonMappers.textNode("$unresolved:" + ref.stringValue());
                }
                return expandNode(target.get(), depth + 1);
            }
            // rebuild the object with schema-bearing children expanded
            TreeMap<String, JsonNode> out = new TreeMap<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if ("properties".equals(key) && value != null && value.isObject()) {
                    TreeMap<String, JsonNode> props = new TreeMap<>();
                    for (Map.Entry<String, JsonNode> p : value.properties()) {
                        props.put(p.getKey(), expandNode(p.getValue(), depth + 1));
                    }
                    out.put(key, JsonMappers.objectNodeFrom(props));
                } else if (("items".equals(key) || "additionalProperties".equals(key) || "schema".equals(key))
                        && value != null && value.isObject()) {
                    out.put(key, expandNode(value, depth + 1));
                } else if ("allOf".equals(key) || "anyOf".equals(key) || "oneOf".equals(key)) {
                    out.put(key, expandSchemaList(value, depth));
                } else {
                    out.put(key, value);
                }
            }
            return JsonMappers.objectNodeFrom(out);
        }
        return node;
    }

    private JsonNode expandSchemaList(JsonNode arrayNode, int depth) {
        List<JsonNode> expanded = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            expanded.add(expandNode(element, depth + 1));
        }
        return JsonMappers.arrayNodeFrom(expanded);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static boolean requiredFlag(JsonNode param) {
        JsonNode required = param.get("required");
        return required != null && required.isBoolean() && required.booleanValue();
    }

    /** pathShape → first raw path template seen at that shape. */
    Map<String, String> getPathAliases() {
        return Map.copyOf(pathAliases);
    }

    /** locationKey ("VERB /shape :: param:in:ordinal") → parameter name. */
    Map<String, String> getParamNames() {
        return Map.copyOf(paramNames);
    }
}
