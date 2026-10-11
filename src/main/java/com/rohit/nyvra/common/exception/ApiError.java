package com.rohit.nyvra.common.exception;

import java.time.Instant;
import java.util.List;

/**
 * Consistent error envelope for all non-2xx responses.
 *
 * @param timestamp when the error was produced (UTC)
 * @param status    HTTP status code
 * @param error     HTTP status reason phrase
 * @param code      stable machine-readable code the client maps to copy: a domain code or the generic per-status
 *                  one from {@link ErrorCodes}
 * @param message   human-readable summary (safe to show; never contains secrets or PII)
 * @param path      request path
 * @param details   optional field-level validation messages
 * @param traceId   correlation id for this request (see {@code common/logging/CorrelationIdFilter}),
 *                  echoed as the {@code X-Request-Id} response header — quote it when reporting an issue
 */
public record ApiError(
    Instant timestamp,
    int status,
    String error,
    String message,
    String code,
    String path,
    List<String> details,
    String traceId) {

    /**
     * Builds an error without a machine-readable code.
     *
     * @param status  HTTP status code
     * @param error   HTTP status reason phrase
     * @param message human-readable summary
     * @param path    request path
     * @param details optional field-level messages; an empty list is stored as {@code null}
     * @param traceId correlation id for the request
     * @return the error stamped with the current time
     */
    public static ApiError of(
            int status, String error, String message, String path, List<String> details, String traceId) {
        return of(status, error, message, null, path, details, traceId);
    }

    /**
     * Builds an error with a machine-readable code.
     *
     * @param status  HTTP status code
     * @param error   HTTP status reason phrase
     * @param message human-readable summary
     * @param code    stable machine-readable code, see {@link ErrorCodes}; may be {@code null}
     * @param path    request path
     * @param details optional field-level messages; an empty list is stored as {@code null}
     * @param traceId correlation id for the request
     * @return the error stamped with the current time
     */
    public static ApiError of(
            int status, String error, String message, String code, String path, List<String> details,
            String traceId) {
        return new ApiError(
            Instant.now(), status, error, message, code, path,
            details == null || details.isEmpty() ? null : details,
            traceId);
    }
}
