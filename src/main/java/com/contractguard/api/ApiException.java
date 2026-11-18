package com.contractguard.api;

import org.springframework.http.HttpStatus;

/**
 * Base for workflow exceptions the controller maps to a JSON error body
 * ({@code {"error": message}}) with the carried HTTP status.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
