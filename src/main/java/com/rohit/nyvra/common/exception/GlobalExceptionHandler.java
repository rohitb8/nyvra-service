package com.rohit.nyvra.common.exception;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

    /**
     * Maps an unknown (or foreign) resource to 404 {@code NOT_FOUND}.
     *
     * @param ex  the not-found exception
     * @param req the current request
     * @return the 404 response
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), ErrorCodes.NOT_FOUND, req, null);
    }

    /**
     * Maps a Bean Validation failure on a request body to 400 {@code VALIDATION_FAILED}.
     *
     * @param ex  the validation exception
     * @param req the current request
     * @return the 400 response with one {@code field: message} detail per violation
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
            .map(GlobalExceptionHandler::formatFieldError)
            .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", ErrorCodes.VALIDATION_FAILED, req, details);
    }

    /**
     * {@code @Min}/{@code @Max} on request parameters, mapped to 400 {@code VALIDATION_FAILED}.
     *
     * @param ex  the parameter-validation exception
     * @param req the current request
     * @return the 400 response with one {@code parameter: message} detail per violation
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException ex, HttpServletRequest req) {
        List<String> details = ex.getAllValidationResults().stream()
            .flatMap(r -> r.getResolvableErrors().stream()
                .map(e -> "%s: %s".formatted(r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
            .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", ErrorCodes.VALIDATION_FAILED, req, details);
    }

    /**
     * Malformed JSON or a value of the wrong shape (e.g. an unknown enum constant).
     *
     * @param ex  the unreadable-body exception
     * @param req the current request
     * @return the 400 {@code BAD_REQUEST} response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or invalid request body", ErrorCodes.BAD_REQUEST, req, null);
    }

    /**
     * A path/query value that can't be converted, e.g. a non-UUID id or an unknown enum constant.
     *
     * @param ex  the type-mismatch exception
     * @param req the current request
     * @return the 400 {@code BAD_REQUEST} response naming the offending parameter
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Invalid value for '%s'".formatted(ex.getName()), ErrorCodes.BAD_REQUEST, req, null);
    }

    /**
     * Maps an {@link AccessDeniedException} thrown from application code to 403 {@code FORBIDDEN}.
     *
     * @param ex  the access-denied exception
     * @param req the current request
     * @return the 403 response
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "Access denied", ErrorCodes.FORBIDDEN, req, null);
    }

    /**
     * Maps a {@link ConflictException} to 409, using its domain code or the generic {@code CONFLICT}.
     *
     * @param ex  the conflict exception
     * @param req the current request
     * @return the 409 response
     */
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), codeOrDefault(ex.code(), ErrorCodes.CONFLICT), req, null);
    }

    /**
     * Maps a {@link BadRequestException} to 400 {@code BAD_REQUEST}.
     *
     * @param ex  the bad-request exception
     * @param req the current request
     * @return the 400 response
     */
    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiError> handleBadRequest(BadRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), ErrorCodes.BAD_REQUEST, req, null);
    }

    /**
     * A DB constraint violation (e.g. a unique-key clash) surfacing all the way up is a 409, not a 500.
     *
     * @param ex  the integrity-violation exception (its message is never echoed)
     * @param req the current request
     * @return the 409 {@code CONFLICT} response
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "The request conflicts with existing data", ErrorCodes.CONFLICT, req, null);
    }

    /**
     * Maps an {@link UnprocessableEntityException} to 422, using its domain code or the generic
     * {@code UNPROCESSABLE}.
     *
     * @param ex  the domain-rule exception
     * @param req the current request
     * @return the 422 response
     */
    @ExceptionHandler(UnprocessableEntityException.class)
    ResponseEntity<ApiError> handleUnprocessableEntity(UnprocessableEntityException ex, HttpServletRequest req) {
        return build(
            HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), codeOrDefault(ex.code(), ErrorCodes.UNPROCESSABLE),
            req, null);
    }

    /**
     * Maps a {@link RateLimitExceededException} to 429 {@code RATE_LIMITED}.
     *
     * @param ex  the rate-limit exception
     * @param req the current request
     * @return the 429 response
     */
    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ApiError> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), ErrorCodes.RATE_LIMITED, req, null);
    }

    /**
     * An upload larger than the configured multipart limit, mapped to 413 {@code PAYLOAD_TOO_LARGE}.
     *
     * @param ex  the size-limit exception
     * @param req the current request
     * @return the 413 response
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handlePayloadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "The upload is too large", ErrorCodes.PAYLOAD_TOO_LARGE, req, null);
    }

    /**
     * A request body in a content type the endpoint does not accept, mapped to 415
     * {@code UNSUPPORTED_MEDIA_TYPE}.
     *
     * @param ex  the media-type exception
     * @param req the current request
     * @return the 415 response
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return build(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type", ErrorCodes.UNSUPPORTED_MEDIA_TYPE, req,
            null);
    }

    /**
     * Catch-all: any unhandled exception becomes a 500 {@code INTERNAL_ERROR}.
     *
     * @param ex  the unexpected exception (its message is never echoed)
     * @param req the current request
     * @return the 500 response
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        // Do not leak the exception message on 500s.
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", ErrorCodes.INTERNAL_ERROR, req, null);
    }

    /** Falls back to the generic per-status code when an exception carries no domain code. */
    private static String codeOrDefault(String code, String fallback) {
        return code != null ? code : fallback;
    }

    /** Renders a field error as {@code field: message}. */
    private static String formatFieldError(FieldError fe) {
        return "%s: %s".formatted(fe.getField(), fe.getDefaultMessage());
    }

    /** Builds the {@link ApiError} response for {@code status}, stamping path and trace id. */
    private static ResponseEntity<ApiError> build(
            HttpStatus status, String message, String code, HttpServletRequest req, List<String> details) {
        ApiError body = ApiError.of(
            status.value(), status.getReasonPhrase(), message, code, req.getRequestURI(), details,
            MDC.get("traceId"));
        return ResponseEntity.status(status).body(body);
    }
}
