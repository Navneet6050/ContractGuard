package com.contractguard.specs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Effective-schema extraction: for every wire location (method + path shape + scope)
 * produces a $ref-free, inline-expanded schema rendered as canonical JSON. Component
 * names and $ref placement disappear here, so quote v1.0.0 (named components, $refs)
 * and v1.1.0 (inlined objects, renamed components) produce IDENTICAL maps when their
 * wire contract is the same.
 *
 * <p>Circular references are cut with an active-reference stack: re-entering a
 * component already being expanded emits an opaque marker {@code {"x-circular":
 * "<property path>"}} whose path contains only wire-visible property names — so two
 * specifications with the same wire-level cycle produce identical markers even when
 * their component names differ. Media-type keys are normalized (lowercased, charset
 * and other parameters stripped) so {@code application/json} and
 * {@code application/json; charset=utf-8} stay one wire location.
 *
 * <p>{@code allOf} branches are merged into their containing schema (intersection
 * semantics: union of properties with recursive same-name merge, union of required,
 * intersected enums, tightest-wins constraints) whenever the branches do not
 * conflict; a conflicting merge keeps the plain {@code allOf} list so the engine's
 * positional composition comparison still sees something. This makes the common
 * "move fields into an allOf branch" reorganization wire-neutral. {@code anyOf}/
 * {@code oneOf} stay as lists (branch-wise comparison is the engine's job).
 *
 * <p>Scopes: "requestBody:<mediaType>", "param:<in>:<ordinal>" (one entry per parameter
 * position; OAS override rules honored; the value is an envelope
 * {"required": bool, "schema": <expanded schema|null>} — the parameter NAME is
 * deliberately excluded so that renaming a parameter does not change the wire shape),
 * "response:<status>[:<mediaType>]" (a documented status without a schema is recorded
 * as the literal "null").
 *
 * <p>Also collects a pathShape → rawPath alias map (first raw path wins) so findings can
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
            ObjectNode envelope = JsonNodeFactory.instance.objectNode();
            envelope.put("required", requiredFlag(param));
            JsonNode content = param.get("content");
            if (content != null && content.isObject()) {
                // media-type parameter (OAS "content" form): the envelope schema carries
                // each accepted media type under a distinguishing extension key
                TreeMap<String, JsonNode> mediaSchemas = new TreeMap<>();
                for (Map.Entry<String, JsonNode> mediaEntry : content.properties()) {
                    JsonNode mediaValue = mediaEntry.getValue();
                    JsonNode schema = mediaValue == null ? null : mediaValue.get("schema");
                    mediaSchemas.put(normalizeMedia(mediaEntry.getKey()),
                            schema != null ? expandSchema(schema) : JsonNodeFactory.instance.nullNode());
                }
                TreeMap<String, JsonNode> wrapper = new TreeMap<>();
                wrapper.put("x-content", JsonMappers.objectNodeFrom(mediaSchemas));
                envelope.set("schema", JsonMappers.objectNodeFrom(wrapper));
            } else {
                JsonNode schema = param.get("schema");
                envelope.set("schema", schema != null ? expandSchema(schema)
                        : JsonNodeFactory.instance.nullNode());
            }
            addSerializationEnvelope(param, in, envelope);
            out.put(locationKey,
                    JsonMappers.writeCanonical(CanonicalJson.canonicalize(envelope)));
        }

        // --- request body ---
        JsonNode requestBody = resolveRefChain(operation.get("requestBody"));
        if (requestBody != null && requestBody.isObject()) {
            JsonNode content = requestBody.get("content");
            if (content != null && content.isObject()) {
                for (Map.Entry<String, JsonNode> mediaType : content.properties()) {
                    JsonNode schema = mediaType.getValue() == null ? null : mediaType.getValue().get("schema");
                    String media = normalizeMedia(mediaType.getKey());
                    String scope = media.isEmpty() ? "requestBody" : "requestBody:" + media;
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
                JsonNode response = resolveRefChain(resp.getValue());
                if (response == null || !response.isObject()) {
                    continue;
                }
                JsonNode content = response.get("content");
                if (content != null && content.isObject()) {
                    for (Map.Entry<String, JsonNode> mediaType : content.properties()) {
                        JsonNode schema = mediaType.getValue() == null ? null : mediaType.getValue().get("schema");
                        String media = normalizeMedia(mediaType.getKey());
                        String scope = "response:" + status + (media.isEmpty() ? "" : ":" + media);
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
                    // component-level parameters are defined once and referenced via
                    // {$ref}; resolve before merging so (in, name) overrides and the
                    // ordinal assignment see the actual parameter object
                    list.add(resolveRefChain(element));
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
     * Inline-expands every local $ref (recursively, cycle-guarded) and renders the
     * result as canonical JSON (sorted keys, sorted required arrays).
     */
    String expand(JsonNode schema) {
        Object canonical = CanonicalJson.canonicalize(
                expandNode(schema, 0, new ArrayDeque<>(), new ArrayList<>()));
        return JsonMappers.writeCanonical(canonical);
    }

    /** Expands a schema and returns its expanded node (not the canonical string). */
    private JsonNode expandSchema(JsonNode schema) {
        return expandNode(schema, 0, new ArrayDeque<>(), new ArrayList<>());
    }

    /**
     * Active-reference stack: a $ref pointing at a component that is already being
     * expanded (a cycle) is cut with an opaque marker instead of recursing until the
     * depth cap. The marker path records only wire-visible property steps, so the
     * same wire-level cycle produces the same marker regardless of component names
     * (rename-neutral, like every other component artifact).
     */
    private JsonNode expandNode(JsonNode node, int depth, Deque<String> activeRefs,
                                List<String> path) {
        if (node == null || node.isMissingNode()) {
            return JsonMappers.missingNode();
        }
        if (depth > MAX_EXPANSION_DEPTH) {
            // reachable only for cycle-free but pathologically deep documents
            return markerNode("x-depth-limit", wirePath(path));
        }
        if (!node.isObject()) {
            return node;
        }
        JsonNode ref = node.get("$ref");
        if (ref != null && ref.isString() && RefResolver.isLocalRef(ref.stringValue())) {
            return expandRef(ref.stringValue(), depth, activeRefs, path);
        }
        if (node.get("allOf") != null && node.get("allOf").isArray()) {
            JsonNode merged = mergeAllOf(node, depth, activeRefs, path);
            if (merged != null) {
                return merged;
            }
            // conflicting branches: fall through and keep the expanded allOf list so
            // the engine's positional composition comparison still applies
        }
        return expandObject(node, depth, activeRefs, path);
    }

    private JsonNode expandRef(String pointer, int depth, Deque<String> activeRefs,
                               List<String> path) {
        if (activeRefs.contains(pointer)) {
            return markerNode("x-circular", wirePath(path));
        }
        Optional<JsonNode> target = RefResolver.resolve(root, pointer);
        if (target.isEmpty()) {
            // validator will have rejected the document; emit a marker
            return JsonMappers.textNode("$unresolved:" + pointer);
        }
        activeRefs.push(pointer);
        try {
            return expandNode(target.get(), depth + 1, activeRefs, path);
        } finally {
            activeRefs.pop();
        }
    }

    /** Rebuilds a schema object with its schema-bearing children expanded. */
    private JsonNode expandObject(JsonNode node, int depth, Deque<String> activeRefs,
                                  List<String> path) {
        TreeMap<String, JsonNode> out = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            out.put(entry.getKey(), expandChild(entry.getKey(), entry.getValue(), depth, activeRefs, path));
        }
        return JsonMappers.objectNodeFrom(out);
    }

    private JsonNode expandChild(String key, JsonNode value, int depth,
                                 Deque<String> activeRefs, List<String> path) {
        if (value == null) {
            return null;
        }
        if ("properties".equals(key) && value.isObject()) {
            TreeMap<String, JsonNode> props = new TreeMap<>();
            for (Map.Entry<String, JsonNode> p : value.properties()) {
                path.add(p.getKey());
                try {
                    props.put(p.getKey(), expandNode(p.getValue(), depth + 1, activeRefs, path));
                } finally {
                    path.remove(path.size() - 1);
                }
            }
            return JsonMappers.objectNodeFrom(props);
        }
        if ("items".equals(key) && value.isObject()) {
            path.add("[]");
            try {
                return expandNode(value, depth + 1, activeRefs, path);
            } finally {
                path.remove(path.size() - 1);
            }
        }
        if ("additionalProperties".equals(key) && value.isObject()) {
            path.add("[*]");
            try {
                return expandNode(value, depth + 1, activeRefs, path);
            } finally {
                path.remove(path.size() - 1);
            }
        }
        if (("schema".equals(key) || "not".equals(key)) && value.isObject()) {
            return expandNode(value, depth + 1, activeRefs, path);
        }
        if ("allOf".equals(key) || "anyOf".equals(key) || "oneOf".equals(key)) {
            List<JsonNode> expanded = new ArrayList<>();
            for (JsonNode element : value) {
                expanded.add(element == null ? null : expandNode(element, depth + 1, activeRefs, path));
            }
            return JsonMappers.arrayNodeFrom(expanded);
        }
        return value;
    }

    /** Opaque, equality-stable marker object for cut cycles / depth limits. */
    private static JsonNode markerNode(String key, String value) {
        TreeMap<String, JsonNode> marker = new TreeMap<>();
        marker.put(key, JsonMappers.textNode(value));
        return JsonMappers.objectNodeFrom(marker);
    }

    /** Renders the wire-visible property path for markers ("a.b[]"); empty path → ".". */
    private static String wirePath(List<String> path) {
        return path.isEmpty() ? "." : String.join(".", path);
    }

    /**
     * Follows chains of local {$ref} wrappers (component parameters, requestBodies and
     * responses are commonly defined once under components and referenced); returns the
     * first node that is not itself a local $ref wrapper.
     */
    private JsonNode resolveRefChain(JsonNode node) {
        for (int hops = 0; node != null && node.isObject() && hops <= MAX_EXPANSION_DEPTH; hops++) {
            JsonNode ref = node.get("$ref");
            if (ref == null || !ref.isString() || !RefResolver.isLocalRef(ref.stringValue())) {
                return node;
            }
            Optional<JsonNode> target = RefResolver.resolve(root, ref.stringValue());
            if (target.isEmpty()) {
                return node; // validator rejects unresolvable references
            }
            node = target.get();
        }
        return node;
    }

    /**
     * Normalizes a content-map key: lowercased, media parameters (charset etc.)
     * stripped, so "application/json; charset=utf-8" and "application/json" are one
     * wire location.
     */
    static String normalizeMedia(String mediaType) {
        if (mediaType == null) {
            return "";
        }
        String media = mediaType.trim();
        int parameters = media.indexOf(';');
        if (parameters >= 0) {
            media = media.substring(0, parameters).trim();
        }
        return media.toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // allOf merging (intersection semantics)
    // ------------------------------------------------------------------

    /**
     * Merges the allOf branches of a schema object into a single schema: union of
     * properties (recursive same-name merge), union of required, intersected enums,
     * boolean-OR uniqueItems, additionalProperties tightened (false beats a schema
     * object, which beats true) and numeric bounds tightest-wins. Any pairwise
     * conflict returns null and the caller keeps the expanded allOf list instead —
     * no value is ever guessed.
     */
    private JsonNode mergeAllOf(JsonNode node, int depth, Deque<String> activeRefs,
                                List<String> path) {
        JsonNode allOf = node.get("allOf");
        List<TreeMap<String, JsonNode>> branches = new ArrayList<>();
        for (JsonNode branch : allOf) {
            JsonNode expanded = expandNode(branch, depth + 1, activeRefs, path);
            if (expanded == null || !expanded.isObject()) {
                return null; // non-object branch: not mergeable, keep the list
            }
            branches.add(fieldMap(expanded));
        }
        TreeMap<String, JsonNode> merged = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if ("allOf".equals(entry.getKey())) {
                continue;
            }
            merged.put(entry.getKey(), expandChild(entry.getKey(), entry.getValue(), depth, activeRefs, path));
        }
        for (TreeMap<String, JsonNode> branch : branches) {
            merged = mergeFieldMaps(merged, branch);
            if (merged == null) {
                return null;
            }
        }
        return JsonMappers.objectNodeFrom(merged);
    }

    private static TreeMap<String, JsonNode> fieldMap(JsonNode object) {
        TreeMap<String, JsonNode> map = new TreeMap<>();
        if (object != null && object.isObject()) {
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                map.put(entry.getKey(), entry.getValue());
            }
        }
        return map;
    }

    /** Lower bounds: the LARGER value is tighter. Upper bounds: the SMALLER value is tighter. */
    private static final Set<String> LOWER_BOUNDS = Set.of(
            "minimum", "minLength", "minItems", "minProperties", "exclusiveMinimum");
    private static final Set<String> UPPER_BOUNDS = Set.of(
            "maximum", "maxLength", "maxItems", "maxProperties", "exclusiveMaximum");

    private TreeMap<String, JsonNode> mergeFieldMaps(TreeMap<String, JsonNode> acc,
                                                     TreeMap<String, JsonNode> branch) {
        TreeMap<String, JsonNode> out = new TreeMap<>(acc);
        for (Map.Entry<String, JsonNode> entry : branch.entrySet()) {
            String key = entry.getKey();
            JsonNode b = entry.getValue();
            JsonNode a = out.get(key);
            if (a == null) {
                out.put(key, b);
                continue;
            }
            switch (key) {
                case "properties" -> {
                    TreeMap<String, JsonNode> mergedProps = mergePropertyMaps(a, b);
                    if (mergedProps == null) {
                        return null;
                    }
                    out.put(key, JsonMappers.objectNodeFrom(mergedProps));
                }
                case "required" -> out.put(key, unionStringArrays(a, b));
                case "enum" -> {
                    JsonNode intersection = intersectArrays(a, b);
                    if (intersection == null) {
                        return null;
                    }
                    out.put(key, intersection);
                }
                case "items" -> {
                    JsonNode mergedItems = a.isObject() && b.isObject()
                            ? mergedSchemaObject(a, b)
                            : (nodesEqual(a, b) ? a : null);
                    if (mergedItems == null) {
                        return null;
                    }
                    out.put(key, mergedItems);
                }
                case "additionalProperties" -> {
                    JsonNode mergedAdditional = mergeAdditionalProperties(a, b);
                    if (mergedAdditional == null) {
                        return null;
                    }
                    out.put(key, mergedAdditional);
                }
                case "uniqueItems" -> {
                    if (a.isBoolean() && b.isBoolean()) {
                        out.put(key, a.booleanValue() || b.booleanValue()
                                ? JsonNodeFactory.instance.booleanNode(true) : a);
                    } else if (nodesEqual(a, b)) {
                        out.put(key, a);
                    } else {
                        return null;
                    }
                }
                default -> {
                    JsonNode mergedLeaf = mergeLeaf(key, a, b);
                    if (mergedLeaf == null) {
                        return null;
                    }
                    out.put(key, mergedLeaf);
                }
            }
        }
        return out;
    }

    /** Equal-or-absent semantics for everything else; null on conflict. */
    private static JsonNode mergeLeaf(String key, JsonNode a, JsonNode b) {
        if (LOWER_BOUNDS.contains(key) && a.isNumber() && b.isNumber()) {
            return compareNumbers(a, b) >= 0 ? a : b;
        }
        if (UPPER_BOUNDS.contains(key) && a.isNumber() && b.isNumber()) {
            return compareNumbers(a, b) <= 0 ? a : b;
        }
        return nodesEqual(a, b) ? a : null;
    }

    private static int compareNumbers(JsonNode a, JsonNode b) {
        return a.decimalValue().compareTo(b.decimalValue());
    }

    private TreeMap<String, JsonNode> mergePropertyMaps(JsonNode a, JsonNode b) {
        if (!a.isObject() || !b.isObject()) {
            return null;
        }
        TreeMap<String, JsonNode> out = fieldMap(a);
        for (Map.Entry<String, JsonNode> entry : fieldMap(b).entrySet()) {
            JsonNode existing = out.get(entry.getKey());
            if (existing == null) {
                out.put(entry.getKey(), entry.getValue());
                continue;
            }
            JsonNode mergedChild = mergeSchemaNodes(existing, entry.getValue());
            if (mergedChild == null) {
                return null;
            }
            out.put(entry.getKey(), mergedChild);
        }
        return out;
    }

    /** Recursively merges two already-expanded schema nodes; null on conflict. */
    private JsonNode mergeSchemaNodes(JsonNode a, JsonNode b) {
        if (nodesEqual(a, b)) {
            return a;
        }
        if (!a.isObject() || !b.isObject()) {
            return null;
        }
        TreeMap<String, JsonNode> merged = mergeFieldMaps(fieldMap(a), fieldMap(b));
        return merged == null ? null : JsonMappers.objectNodeFrom(merged);
    }

    private JsonNode mergedSchemaObject(JsonNode a, JsonNode b) {
        TreeMap<String, JsonNode> merged = mergeFieldMaps(fieldMap(a), fieldMap(b));
        return merged == null ? null : JsonMappers.objectNodeFrom(merged);
    }

    /** Intersection of additionalProperties (false beats a schema object, which beats true). */
    private static JsonNode mergeAdditionalProperties(JsonNode a, JsonNode b) {
        if (a.isObject() && b.isObject()) {
            TreeMap<String, JsonNode> mergedMap = fieldMap(a);
            for (Map.Entry<String, JsonNode> entry : fieldMap(b).entrySet()) {
                JsonNode existing = mergedMap.get(entry.getKey());
                if (existing == null) {
                    mergedMap.put(entry.getKey(), entry.getValue());
                } else if (!nodesEqual(existing, entry.getValue())) {
                    return null; // conflicting object schemas: keep the allOf list
                }
            }
            return JsonMappers.objectNodeFrom(mergedMap);
        }
        if (a.isBoolean() && b.isBoolean()) {
            return a.booleanValue() && b.booleanValue()
                    ? a : JsonNodeFactory.instance.booleanNode(false);
        }
        // one boolean, one schema object (or invalid other kind)
        JsonNode boolSide = a.isBoolean() ? a : (b.isBoolean() ? b : null);
        JsonNode objectSide = a.isObject() ? a : (b.isObject() ? b : null);
        if (boolSide == null) {
            return nodesEqual(a, b) ? a : null;
        }
        if (objectSide == null) {
            return null;
        }
        return boolSide.booleanValue()
                ? objectSide
                : JsonNodeFactory.instance.booleanNode(false);
    }

    private static JsonNode unionStringArrays(JsonNode a, JsonNode b) {
        Set<String> names = new java.util.TreeSet<>();
        for (JsonNode node : List.of(a, b)) {
            if (node != null && node.isArray()) {
                for (JsonNode element : node) {
                    names.add(element.asText());
                }
            }
        }
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (String name : names) {
            out.add(name);
        }
        return out;
    }

    /** Element-wise intersection; null when the result would be empty (keep the allOf list). */
    private static JsonNode intersectArrays(JsonNode a, JsonNode b) {
        if (!a.isArray() || !b.isArray()) {
            return nodesEqual(a, b) ? a : null;
        }
        List<JsonNode> kept = new ArrayList<>();
        for (JsonNode element : a) {
            for (JsonNode other : b) {
                if (nodesEqual(element, other)) {
                    kept.add(element);
                    break;
                }
            }
        }
        return kept.isEmpty() ? null : JsonMappers.arrayNodeFrom(kept);
    }

    private static boolean nodesEqual(JsonNode a, JsonNode b) {
        return a == null ? b == null : a.equals(b);
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

    /**
     * Records parameter serialization attributes (style / explode / allowReserved) in
     * the envelope under {@code "x-serialization"} — these change how the parameter
     * value appears on the wire, so two envelopes that differ here are not
     * wire-identical. Only DEVIATIONS from the OpenAPI defaults are recorded
     * (style: "form" for query/cookie, "simple" for path/header; explode: true for
     * form style, false otherwise; allowReserved: false, query-only per OAS 3.0), so
     * spelling an implicit default out explicitly stays wire-neutral and produces an
     * identical envelope. Content-form parameters carry their own media map; their
     * serialization attributes are still recorded when present.
     */
    private static void addSerializationEnvelope(JsonNode param, String in, ObjectNode envelope) {
        String defaultStyle = "query".equals(in) || "cookie".equals(in) ? "form" : "simple";
        JsonNode styleNode = param.get("style");
        String style = styleNode != null && styleNode.isString() && !styleNode.stringValue().isBlank()
                ? styleNode.stringValue()
                : defaultStyle;
        boolean defaultExplode = "form".equals(style);
        JsonNode explodeNode = param.get("explode");
        boolean explode = explodeNode != null && explodeNode.isBoolean()
                ? explodeNode.booleanValue()
                : defaultExplode;
        TreeMap<String, JsonNode> serialization = new TreeMap<>();
        if (!style.equals(defaultStyle)) {
            serialization.put("style", JsonMappers.textNode(style));
        }
        if (explode != defaultExplode) {
            serialization.put("explode", JsonNodeFactory.instance.booleanNode(explode));
        }
        JsonNode allowReserved = param.get("allowReserved");
        if ("query".equals(in) && allowReserved != null && allowReserved.isBoolean()
                && allowReserved.booleanValue()) {
            serialization.put("allowReserved", JsonNodeFactory.instance.booleanNode(true));
        }
        if (!serialization.isEmpty()) {
            envelope.set("x-serialization", JsonMappers.objectNodeFrom(serialization));
        }
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
