package com.rohit.nyvra.common.exception;

/** Thrown when a request conflicts with the current state of the resource (HTTP 409). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
