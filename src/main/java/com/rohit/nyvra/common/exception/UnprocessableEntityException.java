package com.rohit.nyvra.common.exception;

/**
 * Thrown when a request is well-formed and passes Bean Validation but violates a business/domain
 * rule (HTTP 422) — e.g. a {@code FINANCIAL_RULES.md} constraint. Distinct from
 * {@link org.springframework.web.bind.MethodArgumentNotValidException} (400), which covers
 * structural/field-level validation only.
 */
public class UnprocessableEntityException extends RuntimeException {

    public UnprocessableEntityException(String message) {
        super(message);
    }
}
