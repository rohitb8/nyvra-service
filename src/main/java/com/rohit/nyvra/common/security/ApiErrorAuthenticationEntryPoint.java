package com.rohit.nyvra.common.security;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.nyvra.common.exception.ApiError;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Writes an {@link ApiError} body for a missing/invalid bearer token (HTTP 401).
 *
 * <p>This runs at the security-filter level, before Spring MVC dispatch — {@code @RestControllerAdvice}
 * (see {@code GlobalExceptionHandler}) never sees authentication failures, only exceptions thrown
 * during controller/service execution. Without this, Spring Security's default entry point returns a
 * bare {@code WWW-Authenticate} response with no body matching the rest of the API's error shape.
 */
public class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ApiErrorAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException, ServletException {
        ApiError body = ApiError.of(
            HttpStatus.UNAUTHORIZED.value(),
            HttpStatus.UNAUTHORIZED.getReasonPhrase(),
            "Missing or invalid access token",
            request.getRequestURI(),
            null,
            MDC.get("traceId"));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
