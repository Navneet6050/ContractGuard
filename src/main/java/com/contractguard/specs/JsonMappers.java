package com.contractguard.specs;

import java.util.List;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Shared Jackson 3 helpers for building nodes and rendering canonical JSON.
 *
 * Canonical JSON is always rendered from the plain Map/List structures produced by
 * {@link CanonicalJson} (TreeMap-backed, sorted) or from TreeMap-ordered ObjectNodes,
 * so equal structures always serialize to equal strings regardless of input key order.
 */
final class JsonMappers {

    private static final JsonMapper CANONICAL = JsonMapper.builder().build();

    private JsonMappers() {
    }

    /** Serializes a canonical Map/List (or any JSON-serializable value) deterministically. */
    static String writeCanonical(Object value) {
        return CANONICAL.writeValueAsString(value);
    }

    /** Builds an ObjectNode with keys in the iteration order of the given map (sorted). */
    static ObjectNode objectNodeFrom(TreeMap<String, JsonNode> source) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        source.forEach(out::set);
        return out;
    }

    /** Builds an ArrayNode preserving element order. */
    static ArrayNode arrayNodeFrom(List<JsonNode> values) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode value : values) {
            out.add(value);
        }
        return out;
    }

    static StringNode textNode(String value) {
        return JsonNodeFactory.instance.stringNode(value);
    }

    static JsonNode missingNode() {
        return JsonNodeFactory.instance.missingNode();
    }
}
