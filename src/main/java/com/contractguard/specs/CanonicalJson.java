package com.contractguard.specs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;

/**
 * Canonicalization: converts a Jackson tree into a plain Map/List structure with sorted
 * object keys and a sorted {@code required} array (it is semantically a set). Component
 * names and $ref placement are deliberately NOT erased here — that is the effective-schema
 * expander's job — so the whole-document canonical form still distinguishes genuinely
 * different documents while being insensitive to key order and formatting.
 */
final class CanonicalJson {

    private CanonicalJson() {
    }

    /** Canonicalizes a node into JSON-serializable Maps/Lists (sorted keys). */
    static Object canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if ("required".equals(key) && value.isArray()) {
                    sorted.put(key, sortedTextList(value));
                } else {
                    sorted.put(key, canonicalize(value));
                }
            }
            return sorted;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode element : node) {
                list.add(canonicalize(element));
            }
            return list;
        }
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        // null, POJO, binary etc.: fall back to the node itself; Jackson serializes it as-is
        return node;
    }

    private static List<String> sortedTextList(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            String text = element.isString() ? element.stringValue() : element.asText();
            if (text != null) {
                values.add(text);
            }
        }
        java.util.Collections.sort(values);
        return values;
    }
}
