package com.rohit.nyvra.common.exception;

import java.time.Instant;
import java.util.List;

/**
 * Consistent error envelope for all non-2xx responses.
 *
 * @param timestamp when the error was produced (UTC)
 * @param status    HTTP status code
 * @param error     HTTP status reason phrase
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
    String path,
    List<String> details,
    String traceId) {

    public static ApiError of(
            int status, String error, String message, String path, List<String> details, String traceId) {
        return new ApiError(
            Instant.now(), status, error, message, path,
            details == null || details.isEmpty() ? null : details,
            traceId);
    }
}
