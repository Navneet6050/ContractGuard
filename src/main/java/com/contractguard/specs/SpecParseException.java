package com.contractguard.specs;

import java.util.List;

/**
 * Thrown when a specification fails parsing or structural validation. Carries every
 * collected error (never just the first) so callers can render a complete diagnosis.
 */
public class SpecParseException extends RuntimeException {

    private final List<SpecValidationError> errors;

    public SpecParseException(List<SpecValidationError> errors) {
        super("spec validation failed with " + errors.size() + " error(s): "
                + String.join("; ", errors.stream().map(SpecValidationError::toString).toList()));
        this.errors = List.copyOf(errors);
    }

    public List<SpecValidationError> getErrors() {
        return errors;
    }
}
