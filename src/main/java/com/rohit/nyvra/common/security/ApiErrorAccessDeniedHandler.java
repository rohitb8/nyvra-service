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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Writes an {@link ApiError} body when an authenticated caller is denied by {@code @PreAuthorize}
 * (HTTP 403). Same rationale as {@link ApiErrorAuthenticationEntryPoint} — this is a filter-chain-level
 * concern, so {@code GlobalExceptionHandler}'s {@code @ExceptionHandler(AccessDeniedException.class)}
 * never actually sees this path; that handler stays only for the rare case application code throws
 * {@link AccessDeniedException} directly.
 */
public class ApiErrorAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ApiErrorAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException, ServletException {
        ApiError body = ApiError.of(
            HttpStatus.FORBIDDEN.value(),
            HttpStatus.FORBIDDEN.getReasonPhrase(),
            "Access denied",
            request.getRequestURI(),
            null,
            MDC.get("traceId"));
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
