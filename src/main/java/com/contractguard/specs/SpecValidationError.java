package com.contractguard.specs;

import java.util.List;

/**
 * One structural validation error, with the JSON pointer of the offending node.
 * Collected in bulk: validation never fails fast, so a single upload reports every
 * problem (e.g. all unresolved $refs at once).
 */
public record SpecValidationError(String pointer, String message) {

    @Override
    public String toString() {
        return pointer + ": " + message;
    }
}
