package com.rohit.nyvra.common.logging;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the caller's identity in MDC (key {@code userId}) once authentication has resolved it, so it
 * appears in every log line for the rest of the request. Instantiated directly (not a
 * {@code @Component}) and registered in {@code SecurityConfig} via {@code addFilterAfter}, positioned
 * right after the bearer-token filter so the {@link Authentication} is already populated.
 *
 * <p>Uses the JWT <b>subject</b> (Keycloak's opaque {@code sub} claim) — deliberately never
 * {@code email} or {@code name}, which are PII. A no-op for unauthenticated requests
 * (health/Swagger), so those simply have no {@code userId} in their logs.
 */
public class UserIdMdcFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        try {
            if (authentication instanceof JwtAuthenticationToken jwtAuth) {
                MDC.put(MDC_KEY, jwtAuth.getToken().getSubject());
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
