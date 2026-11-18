package com.contractguard.specs;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * Scans a specification for {@code deprecated: true} on any object node (fields,
 * operations, parameters) and captures the optional {@code x-sunset} extension.
 * Generic by design: classification into findings is not this class's job.
 *
 * Field paths are rendered in the consumer-file coordinate system: dot-separated
 * property names, {@code []} after array properties, rooted at the schema name
 * (e.g. "Policy.legacyCode", "Bundle.lines[].note").
 */
final class DeprecationScanner {

    private static final int MAX_DEPTH = 500;

    private final JsonNode root;

    DeprecationScanner(JsonNode root) {
        this.root = root;
    }

    List<DeprecationRecord> scan() {
        List<DeprecationRecord> records = new ArrayList<>();
        JsonNode components = root.get("components");
        JsonNode schemas = components == null ? null : components.get("schemas");
        if (schemas != null && schemas.isObject()) {
            for (Map.Entry<String, JsonNode> schemaEntry : schemas.properties()) {
                JsonNode schema = schemaEntry.getValue();
                // a schema may itself be a $ref node — then nothing to scan inside it
                if (schema != null && schema.isObject()) {
                    walkSchema(schema, schemaEntry.getKey(), 0, records);
                }
            }
        }
        return records;
    }

    private void walkSchema(JsonNode node, String fieldPath, int depth, List<DeprecationRecord> records) {
        if (node == null || !node.isObject() || depth > MAX_DEPTH) {
            return;
        }
        JsonNode ref = node.get("$ref");
        if (ref != null && ref.isString()) {
            return; // do not follow refs here: targets are scanned at their own root
        }
        JsonNode deprecated = node.get("deprecated");
        if (deprecated != null && deprecated.isBoolean() && deprecated.booleanValue()) {
            records.add(toRecord(node, fieldPath));
        }
        JsonNode properties = node.get("properties");
        if (properties != null && properties.isObject()) {
            for (Map.Entry<String, JsonNode> prop : properties.properties()) {
                walkSchema(prop.getValue(),
                        fieldPath.isEmpty() ? prop.getKey() : fieldPath + "." + prop.getKey(),
                        depth + 1, records);
            }
        }
        JsonNode items = node.get("items");
        if (items != null && items.isObject()) {
            walkSchema(items, fieldPath + "[]", depth + 1, records);
        }
    }

    private DeprecationRecord toRecord(JsonNode node, String fieldPath) {
        JsonNode sunsetNode = node.get("x-sunset");
        Optional<String> sunsetRaw = sunsetNode != null && sunsetNode.isString()
                ? Optional.of(sunsetNode.stringValue())
                : Optional.empty();
        Optional<LocalDate> sunset = sunsetRaw.flatMap(DeprecationScanner::parseDate);
        return new DeprecationRecord("#/components/schemas/" + fieldPath, fieldPath, sunsetRaw, sunset);
    }

    private static Optional<LocalDate> parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(raw.trim()));
        } catch (DateTimeParseException e) {
            try {
                // tolerate full ISO timestamps ("2026-11-01T00:00:00Z")
                return Optional.of(LocalDate.parse(raw.trim().substring(0, 10)));
            } catch (RuntimeException e2) {
                return Optional.empty();
            }
        }
    }
}
