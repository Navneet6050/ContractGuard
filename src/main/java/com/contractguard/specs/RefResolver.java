package com.contractguard.specs;

import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * Resolves local JSON-pointer references ({@code #/...}) against the document root.
 * Handles pointer escaping ({@code ~0}, {@code ~1}). Used by the structural validator
 * (target must exist) and by the effective-schema extractor (targets are expanded).
 */
final class RefResolver {

    private RefResolver() {
    }

    static boolean isLocalRef(String ref) {
        return ref != null && ref.startsWith("#/");
    }

    /** Resolves a local pointer; empty if the target does not exist. */
    static Optional<JsonNode> resolve(JsonNode root, String ref) {
        if (!isLocalRef(ref)) {
            return Optional.empty();
        }
        JsonNode current = root;
        for (String rawToken : ref.substring(2).split("/", -1)) {
            String token = rawToken.replace("~1", "/").replace("~0", "~");
            if (token.isEmpty() && current != null && !current.isMissingNode()) {
                // "//" or trailing "/": only legal as the root pointer "#/", handled above
                if (ref.equals("#/")) {
                    return Optional.of(current);
                }
                return Optional.empty();
            }
            if (current == null || current.isMissingNode()) {
                return Optional.empty();
            }
            if (current.isArray()) {
                int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
                if (index < 0 || index >= current.size()) {
                    return Optional.empty();
                }
                current = current.get(index);
            } else if (current.isObject()) {
                current = current.get(token);
                if (current == null || current.isMissingNode()) {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        }
        if (current == null || current.isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(current);
    }
}
