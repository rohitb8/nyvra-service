package com.rohit.nyvra.common.logging;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id in MDC (key {@code traceId}) for the lifetime of every request, so it appears
 * in every log line and on every {@link com.rohit.nyvra.common.exception.ApiError}. Instantiated
 * directly (not a {@code @Component}) and registered in {@code SecurityConfig} via
 * {@code addFilterBefore}, to run before authentication so it wraps the whole request — including
 * requests that fail authentication — and to avoid Spring Boot's generic servlet-filter
 * auto-registration also picking it up as a second, separately-ordered filter.
 *
 * <p>Reuses an incoming {@code X-Request-Id} header when present (e.g. from a load balancer or a
 * caller that wants to correlate its own logs), otherwise generates one. Always echoes it back as a
 * response header.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Request and response header carrying the correlation id. */
    static final String HEADER = "X-Request-Id";
    /** MDC key under which the correlation id is exposed to logging. */
    static final String MDC_KEY = "traceId";

    /**
     * Sets the MDC trace id (reusing {@code X-Request-Id} if present), echoes it, and clears it afterwards.
     *
     * @param request the incoming request
     * @param response the response, which gets the {@code X-Request-Id} header
     * @param chain the rest of the filter chain
     * @throws ServletException if the chain fails
     * @throws IOException if the chain fails on I/O
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        try {
            MDC.put(MDC_KEY, traceId);
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
