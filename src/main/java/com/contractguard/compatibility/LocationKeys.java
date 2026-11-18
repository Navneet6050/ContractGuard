package com.contractguard.compatibility;

/**
 * Parser for effective-schema map keys ("VERB /pathShape :: scope"). Key format is
 * produced by the specs module ({@code EffectiveLocation.key()}); this class only
 * reads it — never re-parses YAML or re-canonicalizes schemas.
 */
final class LocationKeys {

    record Operation(String verb, String shape) implements Comparable<Operation> {
        @Override
        public int compareTo(Operation other) {
            int byShape = shape.compareTo(other.shape);
            return byShape != 0 ? byShape : verb.compareTo(other.verb);
        }
    }

    private LocationKeys() {
    }

    static Operation operationOf(String key) {
        int separator = key.indexOf(" :: ");
        String left = separator >= 0 ? key.substring(0, separator) : key;
        int space = left.indexOf(' ');
        if (space <= 0) {
            return new Operation("", left);
        }
        return new Operation(left.substring(0, space), left.substring(space + 1));
    }

    static String scopeOf(String key) {
        int separator = key.indexOf(" :: ");
        return separator >= 0 ? key.substring(separator + 4) : "";
    }

    static boolean isRequestBody(String scope) {
        return scope.startsWith("requestBody");
    }

    static boolean isParam(String scope) {
        return scope.startsWith("param:");
    }

    static boolean isResponse(String scope) {
        return scope.startsWith("response:");
    }

    /** Media type of a requestBody scope ("" when none). */
    static String requestBodyMedia(String scope) {
        return scope.length() > "requestBody".length() && scope.charAt("requestBody".length()) == ':'
                ? scope.substring("requestBody:".length())
                : "";
    }

    /** Status of a response scope ("400" for "response:400:application/json"). */
    static String responseStatus(String scope) {
        String rest = scope.substring("response:".length());
        int colon = rest.indexOf(':');
        return colon < 0 ? rest : rest.substring(0, colon);
    }

    /** Media type of a response scope ("" when the status is documented without content). */
    static String responseMedia(String scope) {
        String rest = scope.substring("response:".length());
        int colon = rest.indexOf(':');
        return colon < 0 ? "" : rest.substring(colon + 1);
    }
}
