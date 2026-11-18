package com.contractguard.api;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.contractguard.specs.SpecValidationError;

/**
 * An uploaded specification failed collect-all validation (policy rule 4: rejected
 * unconditionally, no override possible). Carries every collected error so the response
 * can render the complete diagnosis, plus the persisted rejected attempt (if the service
 * is registered).
 */
public class SpecInvalidException extends ApiException {

    private final List<SpecValidationError> errors;
    private final Long attemptId;

    public SpecInvalidException(List<SpecValidationError> errors, Long attemptId) {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid specification: " + errors.size() + " validation error(s), nothing was stored");
        this.errors = List.copyOf(errors);
        this.attemptId = attemptId;
    }

    public List<SpecValidationError> errors() {
        return errors;
    }

    public Long attemptId() {
        return attemptId;
    }
}
