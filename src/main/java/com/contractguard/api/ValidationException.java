package com.contractguard.api;

import org.springframework.http.HttpStatus;

/**
 * Boundary validation failure on a decision action (PLAN.md §8/§9): a required
 * justification was missing or blank. HTTP 422 per the error map.
 */
public class ValidationException extends ApiException {

    public ValidationException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
