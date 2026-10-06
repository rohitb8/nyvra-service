package com.rohit.nyvra.common.exception;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Turns exceptions into {@link ApiError} responses.
 *
 * <p>401 (missing/invalid token) and 403 (from {@code @PreAuthorize}) never reach here — they're
 * handled at the security-filter level, before Spring MVC dispatch, by
 * {@code common/security/ApiErrorAuthenticationEntryPoint} and
 * {@code common/security/ApiErrorAccessDeniedHandler} (wired in {@code SecurityConfig}), which build
 * the same {@link ApiError} shape. {@link #handleAccessDenied} below only fires for an
 * {@link AccessDeniedException} thrown directly from application code, not the filter-chain case.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
            .map(GlobalExceptionHandler::formatFieldError)
            .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", req, details);
    }

    /** {@code @Min}/{@code @Max} on request parameters. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException ex, HttpServletRequest req) {
        List<String> details = ex.getAllValidationResults().stream()
            .flatMap(r -> r.getResolvableErrors().stream()
                .map(e -> "%s: %s".formatted(r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
            .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", req, details);
    }

    /** Malformed JSON or a value of the wrong shape (e.g. an unknown enum constant). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or invalid request body", req, null);
    }

    /** A path/query value that can't be converted, e.g. a non-UUID id or an unknown enum constant. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Invalid value for '%s'".formatted(ex.getName()), req, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "Access denied", req, null);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), ex.code(), req, null);
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiError> handleBadRequest(BadRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, null);
    }

    /** A DB constraint violation (e.g. a unique-key clash) surfacing all the way up is a 409, not a 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "The request conflicts with existing data", req, null);
    }

    @ExceptionHandler(UnprocessableEntityException.class)
    ResponseEntity<ApiError> handleUnprocessableEntity(UnprocessableEntityException ex, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.code(), req, null);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ApiError> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        // Do not leak the exception message on 500s.
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", req, null);
    }

    private static String formatFieldError(FieldError fe) {
        return "%s: %s".formatted(fe.getField(), fe.getDefaultMessage());
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req, List<String> details) {
        return build(status, message, null, req, details);
    }

    private static ResponseEntity<ApiError> build(
            HttpStatus status, String message, String code, HttpServletRequest req, List<String> details) {
        ApiError body = ApiError.of(
            status.value(), status.getReasonPhrase(), message, code, req.getRequestURI(), details,
            MDC.get("traceId"));
        return ResponseEntity.status(status).body(body);
    }
}
