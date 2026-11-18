package com.contractguard.specs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Collect-all structural validator. Verifies the OAS skeleton (openapi version,
 * info.title, info.version, paths) and walks the entire document collecting EVERY
 * error, in particular every unresolvable local $ref, before the caller decides.
 * This is the authoritative gate for policy rule 4; the Swagger Parser result is an
 * additional signal but never the only one.
 */
final class StructuralValidator {

    private static final int MAX_DEPTH = 500;

    private final JsonNode root;

    StructuralValidator(JsonNode root) {
        this.root = root;
    }

    List<SpecValidationError> validate() {
        List<SpecValidationError> errors = new ArrayList<>();

        JsonNode openapi = root.get("openapi");
        if (openapi == null || !openapi.isString()) {
            errors.add(new SpecValidationError("/openapi", "missing or not a string"));
        } else if (!openapi.stringValue().startsWith("3.0")) {
            errors.add(new SpecValidationError("/openapi",
                    "unsupported OpenAPI version '" + openapi.stringValue() + "' (only 3.0.x is accepted)"));
        }

        JsonNode info = root.get("info");
        if (info == null || !info.isObject()) {
            errors.add(new SpecValidationError("/info", "missing or not an object"));
        } else {
            JsonNode title = info.get("title");
            if (title == null || !title.isString() || title.stringValue().isBlank()) {
                errors.add(new SpecValidationError("/info/title", "missing or blank"));
            }
            JsonNode version = info.get("version");
            if (version == null || !version.isString() || version.stringValue().isBlank()) {
                errors.add(new SpecValidationError("/info/version", "missing or blank"));
            }
        }

        JsonNode paths = root.get("paths");
        if (paths == null || !paths.isObject()) {
            errors.add(new SpecValidationError("/paths", "missing or not an object"));
        }

        walk(root, "", 0, errors);
        return errors;
    }

    private void walk(JsonNode node, String pointer, int depth, List<SpecValidationError> errors) {
        if (depth > MAX_DEPTH) {
            errors.add(new SpecValidationError(pointer, "document nesting exceeds depth limit"));
            return;
        }
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isString()) {
                validateRef(ref.stringValue(), pointer, errors);
                return; // a $ref sibling context is not walked further
            }
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                walk(field.getValue(), childPointer(pointer, field.getKey()), depth + 1, errors);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode element : node) {
                walk(element, pointer + "/" + index++, depth + 1, errors);
            }
        }
    }

    private void validateRef(String ref, String pointer, List<SpecValidationError> errors) {
        if (ref.isBlank()) {
            errors.add(new SpecValidationError(pointer + "/$ref", "empty $ref"));
            return;
        }
        if (!RefResolver.isLocalRef(ref)) {
            errors.add(new SpecValidationError(pointer + "/$ref",
                    "non-local reference '" + ref + "' is not supported (only same-document #/ pointers)"));
            return;
        }
        if (!RefResolver.resolve(root, ref).isPresent()) {
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            errors.add(new SpecValidationError(pointer + "/$ref",
                    "unresolved reference '" + ref + "': target does not exist (missing component '" + name + "')"));
        }
    }

    private static String childPointer(String pointer, String key) {
        // JSON Pointer escaping: ~ -> ~0, / -> ~1
        return pointer + "/" + key.replace("~", "~0").replace("/", "~1");
    }
}
