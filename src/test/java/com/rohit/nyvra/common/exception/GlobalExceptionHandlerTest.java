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

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/whatever");

    @Test
    void unexpectedErrorNeverLeaksTheOriginalMessage() {
        String secret = "sk_live_super_secret_token_value";
        Exception ex = new RuntimeException("failed while using token " + secret);

        ResponseEntity<ApiError> response = handler.handleUnexpected(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("Unexpected error");
        assertThat(response.getBody().message()).doesNotContain(secret);
    }

    @Test
    void conflictExceptionMapsTo409() {
        ResponseEntity<ApiError> response = handler.handleConflict(new ConflictException("already exists"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message()).isEqualTo("already exists");
    }

    @Test
    void dataIntegrityViolationMapsTo409WithoutLeakingConstraintDetail() {
        var ex = new DataIntegrityViolationException(
            "duplicate key value violates unique constraint \"user_profile_keycloak_subject_key\"");

        ResponseEntity<ApiError> response = handler.handleDataIntegrityViolation(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message()).doesNotContain("user_profile_keycloak_subject_key");
    }

    @Test
    void unprocessableEntityExceptionMapsTo422() {
        ResponseEntity<ApiError> response =
            handler.handleUnprocessableEntity(new UnprocessableEntityException("breaches a rule"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().message()).isEqualTo("breaches a rule");
    }

    @Test
    void rateLimitExceededExceptionMapsTo429() {
        ResponseEntity<ApiError> response =
            handler.handleRateLimitExceeded(new RateLimitExceededException("slow down"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
    }
}
