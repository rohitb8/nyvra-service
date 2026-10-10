package com.rohit.nyvra.common.exception;

/**
 * Thrown when a request is well-formed and passes Bean Validation but violates a business/domain
 * rule (HTTP 422) — e.g. a {@code FINANCIAL_RULES.md} constraint. Distinct from
 * {@link org.springframework.web.bind.MethodArgumentNotValidException} (400), which covers
 * structural/field-level validation only.
 */
public class UnprocessableEntityException extends RuntimeException {

    /** Stable machine-readable code; {@code null} means the generic {@code UNPROCESSABLE} applies. */
    private final String code;

    /**
     * Creates a domain-rule violation with no domain code.
     *
     * @param message safe human-readable summary
     */
    public UnprocessableEntityException(String message) {
        this(null, message);
    }

    /**
     * Creates a domain-rule violation with a domain code.
     *
     * @param code    stable machine-readable code, e.g. {@code UNSUPPORTED_CURRENCY}; may be {@code null}
     * @param message safe human-readable summary
     */
    public UnprocessableEntityException(String code, String message) {
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
