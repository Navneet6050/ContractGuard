package com.contractguard.consumers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.contractguard.specs.PathShapes;

import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Parses consumer declaration YAML (kit format):
 *
 * <pre>
 * consumer: mobile-app
 * team: Mobile
 * uses:
 * - provider: quote-api
 *   method: POST
 *   path: /quotes
 *   sends: [vehicle.make, ...]
 *   reads: [premium.breakdown[].amount, ...]
 * </pre>
 *
 * Only this module knows this format. Matching-relevant normalization is limited to
 * method casing (uppercased) and the path shape (via the shared {@link PathShapes});
 * consumer name, provider identifier and field-path strings are preserved verbatim.
 */
public class ConsumerParser {

    private static final Set<String> KNOWN_METHODS = Set.of(
            "GET", "PUT", "POST", "DELETE", "OPTIONS", "HEAD", "PATCH", "TRACE");

    private final YAMLMapper yamlMapper = YAMLMapper.builder().build();

    /** Parses one consumer file. Throws {@link ConsumerParseException} listing ALL problems. */
    public ConsumerDeclaration parse(String yamlContent) {
        return parse(yamlContent, null);
    }

    /** Same, with an optional source label used in error messages. */
    public ConsumerDeclaration parse(String yamlContent, String source) {
        JsonNode root = readYaml(yamlContent, source);
        List<String> errors = new ArrayList<>();

        String name = textOrNull(root, "consumer");
        if (name == null || name.isBlank()) {
            errors.add("'consumer' is required and must be a non-blank string");
        }
        String team = textOrNull(root, "team");
        if (team == null) {
            team = ""; // team is optional metadata
        }

        List<DeclaredOperation> operations = new ArrayList<>();
        JsonNode uses = root.get("uses");
        if (uses == null || uses.isNull()) {
            // a consumer with no declared usage is valid (affects nothing)
        } else if (!uses.isArray()) {
            errors.add("'uses' must be a list");
        } else {
            for (int i = 0; i < uses.size(); i++) {
                JsonNode entry = uses.get(i);
                String label = "uses[" + i + "]";
                if (entry == null || !entry.isObject()) {
                    errors.add(label + " must be a mapping");
                    continue;
                }
                parseOperation(entry, label, errors).ifPresent(operations::add);
            }
        }

        if (!errors.isEmpty()) {
            throw new ConsumerParseException(source, errors);
        }
        return new ConsumerDeclaration(name, team, operations);
    }

    private java.util.Optional<DeclaredOperation> parseOperation(JsonNode entry, String label,
                                                                 List<String> errors) {
        String provider = textOrNull(entry, "provider");
        if (provider == null || provider.isBlank()) {
            errors.add(label + ".provider is required and must be a non-blank string");
        }
        String method = textOrNull(entry, "method");
        if (method == null || method.isBlank()) {
            errors.add(label + ".method is required");
        } else {
            method = method.trim().toUpperCase(java.util.Locale.ROOT);
            if (!KNOWN_METHODS.contains(method)) {
                errors.add(label + ".method '" + method + "' is not a known HTTP verb");
                method = null;
            }
        }
        String rawPath = textOrNull(entry, "path");
        if (rawPath == null || rawPath.isBlank()) {
            errors.add(label + ".path is required and must be a non-blank string");
        } else if (!rawPath.trim().startsWith("/")) {
            errors.add(label + ".path '" + rawPath + "' must start with '/'");
            rawPath = null;
        }

        List<String> sends = stringList(entry.get("sends"), label + ".sends", errors);
        List<String> reads = stringList(entry.get("reads"), label + ".reads", errors);

        if (!errors.isEmpty() || provider == null || method == null || rawPath == null) {
            return java.util.Optional.empty();
        }
        String trimmedPath = rawPath.trim();
        return java.util.Optional.of(new DeclaredOperation(provider.trim(), method, trimmedPath,
                PathShapes.normalize(trimmedPath), sends, reads));
    }

    /** Optional list of non-blank strings, de-duplicated preserving order. */
    private List<String> stringList(JsonNode node, String label, List<String> errors) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            errors.add(label + " must be a list of strings");
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode element : node) {
            if (element == null || !element.isString() || element.stringValue().isBlank()) {
                errors.add(label + " must contain only non-blank strings");
                return List.of();
            }
            out.add(element.stringValue().trim());
        }
        return new ArrayList<>(out);
    }

    private JsonNode readYaml(String content, String source) {
        if (content == null || content.isBlank()) {
            throw new ConsumerParseException(source, List.of("document is empty"));
        }
        try {
            JsonNode node = yamlMapper.readTree(content);
            if (node == null || node.isNull() || !node.isObject()) {
                throw new ConsumerParseException(source, List.of("document is not a YAML mapping (object)"));
            }
            return node;
        } catch (ConsumerParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ConsumerParseException(source, List.of("invalid YAML: " + e.getMessage()));
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
