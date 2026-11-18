package com.contractguard.api;

import org.springframework.http.HttpStatus;

/** Conflicting registration: duplicate service, duplicate version with different content, or a version regression (HTTP 409). */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
