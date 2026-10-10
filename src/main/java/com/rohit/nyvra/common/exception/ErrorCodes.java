package com.rohit.nyvra.common.exception;

/**
 * Generic {@link ApiError#code()} values, one per HTTP status, as listed in {@code API_DESIGN.md} §5.
 * They are the fallback when an exception carries no more specific domain code (e.g.
 * {@code SOURCE_READ_ONLY}); the frontend maps them to microcopy.
 */
public final class ErrorCodes {

    /** 400: malformed JSON, bad path/query value or other request that cannot be understood. */
    public static final String BAD_REQUEST = "BAD_REQUEST";

    /** 400: Bean Validation failure; {@code details} carries {@code field: message} entries. */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /** 401: missing, expired or invalid access token. */
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

    /** 403: authenticated but lacking the required role. */
    public static final String FORBIDDEN = "FORBIDDEN";

    /** 404: unknown id or another user's resource. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** 409: the request conflicts with the current state of a resource. */
    public static final String CONFLICT = "CONFLICT";

    /** 409: an {@code Idempotency-Key} was reused with a different request. */
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    /** 409: a request with the same {@code Idempotency-Key} is still being processed. */
    public static final String IDEMPOTENCY_REQUEST_IN_PROGRESS = "IDEMPOTENCY_REQUEST_IN_PROGRESS";

    /** 413: upload larger than the configured limit. */
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

    /** 415: request content type not supported by the endpoint. */
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";

    /** 422: well-formed request that violates a domain invariant. */
    public static final String UNPROCESSABLE = "UNPROCESSABLE";

    /** 429: per-user rate limit exceeded. */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /** 500: anything unhandled; the message is deliberately generic. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** Constants holder; not instantiable. */
    private ErrorCodes() {
    }
}
