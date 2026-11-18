package com.contractguard.api;

import org.springframework.http.HttpStatus;

/** Unknown service, version or attempt (HTTP 404). */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
