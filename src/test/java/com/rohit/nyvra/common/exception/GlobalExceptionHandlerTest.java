package com.rohit.nyvra.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Exercises each new status mapping directly (no HTTP round-trip needed — these are plain method
 * calls) and, specifically, that a 500 never leaks the original exception's message.
 */
class GlobalExceptionHandlerTest {

    /** The handler under test. */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** A stand-in request the handlers read the path from. */
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/whatever");

    /** A 500 never echoes the exception text and carries {@code INTERNAL_ERROR}. */
    @Test
    void unexpectedErrorNeverLeaksTheOriginalMessage() {
        String secret = "sk_live_super_secret_token_value";
        Exception ex = new RuntimeException("failed while using token " + secret);

        ResponseEntity<ApiError> response = handler.handleUnexpected(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("Unexpected error");
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain(secret);
    }

    /** A plain conflict maps to 409 with the generic {@code CONFLICT} code. */
    @Test
    void conflictExceptionMapsTo409() {
        ResponseEntity<ApiError> response = handler.handleConflict(new ConflictException("already exists"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message()).isEqualTo("already exists");
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
    }

    /** A DB constraint clash maps to 409 without leaking the constraint name. */
    @Test
    void dataIntegrityViolationMapsTo409WithoutLeakingConstraintDetail() {
        var ex = new DataIntegrityViolationException(
            "duplicate key value violates unique constraint \"user_profile_keycloak_subject_key\"");

        ResponseEntity<ApiError> response = handler.handleDataIntegrityViolation(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message()).doesNotContain("user_profile_keycloak_subject_key");
    }

    /** A plain domain-rule violation maps to 422 with the generic {@code UNPROCESSABLE} code. */
    @Test
    void unprocessableEntityExceptionMapsTo422() {
        ResponseEntity<ApiError> response =
            handler.handleUnprocessableEntity(new UnprocessableEntityException("breaches a rule"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().code()).isEqualTo("UNPROCESSABLE");
        assertThat(response.getBody().message()).isEqualTo("breaches a rule");
    }

    /** A rate-limit hit maps to 429 {@code RATE_LIMITED}. */
    @Test
    void rateLimitExceededExceptionMapsTo429() {
        ResponseEntity<ApiError> response =
            handler.handleRateLimitExceeded(new RateLimitExceededException("slow down"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody().code()).isEqualTo("RATE_LIMITED");
    }

    /** A domain code on the exception wins over the generic per-status one. */
    @Test
    void domainCodesOverrideTheGenericOne() {
        assertThat(handler.handleConflict(new ConflictException("SOURCE_READ_ONLY", "read only"), request)
            .getBody().code()).isEqualTo("SOURCE_READ_ONLY");
        assertThat(handler.handleUnprocessableEntity(
            new UnprocessableEntityException("NET_EXCEEDS_GROSS", "net > gross"), request)
            .getBody().code()).isEqualTo("NET_EXCEEDS_GROSS");
    }

    /** A missing resource maps to 404 {@code NOT_FOUND}. */
    @Test
    void notFoundCarriesNotFoundCode() {
        ResponseEntity<ApiError> response = handler.handleNotFound(new ResourceNotFoundException("gone"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    /** A bad request, unreadable body and denied access map to their generic codes. */
    @Test
    void badRequestAndForbiddenCarryGenericCodes() {
        assertThat(handler.handleBadRequest(new BadRequestException("nope"), request).getBody().code())
            .isEqualTo("BAD_REQUEST");
        assertThat(handler.handleAccessDenied(new org.springframework.security.access.AccessDeniedException("x"), request)
            .getBody().code()).isEqualTo("FORBIDDEN");
    }

    /** Oversized uploads and unsupported content types map to 413 and 415. */
    @Test
    void uploadErrorsMapTo413And415() {
        ResponseEntity<ApiError> tooLarge = handler.handlePayloadTooLarge(
            new org.springframework.web.multipart.MaxUploadSizeExceededException(1L), request);
        ResponseEntity<ApiError> badType = handler.handleUnsupportedMediaType(
            new org.springframework.web.HttpMediaTypeNotSupportedException("text/plain"), request);

        assertThat(tooLarge.getStatusCode().value()).isEqualTo(413);
        assertThat(tooLarge.getBody().code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(badType.getStatusCode().value()).isEqualTo(415);
        assertThat(badType.getBody().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }
}
