package com.contractguard.specs;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes endpoint path templates to a shape in which placeholder names are ignored
 * and only their positions count: {@code /quotes/{id}} and {@code /quotes/{quoteId}}
 * both normalize to {@code /quotes/{0}}. This is what lets consumer bindings written
 * against one parameter naming keep matching the same operation after a rename.
 */
public final class PathShapes {

    private PathShapes() {
    }

    public static String normalize(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/";
        }
        String trimmed = rawPath.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        String[] segments = trimmed.split("/", -1);
        List<String> out = new ArrayList<>();
        int ordinal = 0;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue; // leading slash; consecutive slashes collapse
            }
            if (segment.startsWith("{") && segment.endsWith("}") && segment.length() >= 2) {
                out.add("{" + ordinal++ + "}");
            } else {
                out.add(segment);
            }
        }
        return "/" + String.join("/", out);
    }
}
