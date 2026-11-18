package com.contractguard.api;

import org.springframework.http.HttpStatus;

/** Malformed request input: blank fields, wrong-state transitions, missing justification (HTTP 400). */
public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
