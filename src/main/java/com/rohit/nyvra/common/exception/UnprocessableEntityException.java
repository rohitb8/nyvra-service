package com.rohit.nyvra.common.exception;

/**
 * Thrown when a request is well-formed and passes Bean Validation but violates a business/domain
 * rule (HTTP 422) — e.g. a {@code FINANCIAL_RULES.md} constraint. Distinct from
 * {@link org.springframework.web.bind.MethodArgumentNotValidException} (400), which covers
 * structural/field-level validation only.
 */
public class UnprocessableEntityException extends RuntimeException {

    private final String code;

    public UnprocessableEntityException(String message) {
        this(null, message);
    }

    /** @param code stable machine-readable code, e.g. {@code UNSUPPORTED_CURRENCY}; may be {@code null} */
    public UnprocessableEntityException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
