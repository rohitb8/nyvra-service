package com.rohit.nyvra.common.exception;

/** Thrown when a request conflicts with the current state of the resource (HTTP 409). */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String message) {
        this(null, message);
    }

    /** @param code stable machine-readable code, e.g. {@code SOURCE_READ_ONLY}; may be {@code null} */
    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
