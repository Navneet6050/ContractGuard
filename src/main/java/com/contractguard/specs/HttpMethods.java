package com.contractguard.specs;

import java.util.Set;

/**
 * HTTP methods recognized as operations in a Path Item (per OpenAPI: get, put, post,
 * delete, options, head, patch, trace). Everything else under /paths/{path} is a
 * Path Item Object field (parameters, summary, description, servers) and must not be
 * treated as an operation.
 */
final class HttpMethods {

    private static final Set<String> METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");

    private HttpMethods() {
    }

    static boolean isMethod(String key) {
        return key != null && METHODS.contains(key.toLowerCase());
    }
}
