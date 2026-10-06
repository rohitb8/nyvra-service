package com.rohit.nyvra.common.exception;

/**
 * Thrown when a request is structurally wrong in a way Bean Validation can't express, such as fields
 * that are only valid together (HTTP 400).
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
