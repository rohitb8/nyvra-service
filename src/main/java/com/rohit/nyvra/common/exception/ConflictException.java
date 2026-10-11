package com.rohit.nyvra.common.exception;

/** Thrown when a request conflicts with the current state of the resource (HTTP 409). */
public class ConflictException extends RuntimeException {

    /** Stable machine-readable code; {@code null} means the generic {@code CONFLICT} applies. */
    private final String code;

    /**
     * Creates a conflict with no domain code.
     *
     * @param message safe human-readable summary
     */
    public ConflictException(String message) {
        this(null, message);
    }

    /**
     * Creates a conflict with a domain code.
     *
     * @param code    stable machine-readable code, e.g. {@code SOURCE_READ_ONLY}; may be {@code null}
     * @param message safe human-readable summary
     */
    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Returns the domain code.
     *
     * @return the code, or {@code null} when none was given
     */
    public String code() {
        return code;
    }
}
