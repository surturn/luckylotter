package com.lucklotter.service;

/**
 * A request is well-formed but breaks a rule bean validation can't express —
 * typically one spanning two fields. Surfaces as HTTP 400.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
