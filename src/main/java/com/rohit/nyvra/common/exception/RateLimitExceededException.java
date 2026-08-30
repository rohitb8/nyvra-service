package com.rohit.nyvra.common.exception;

/**
 * Thrown when a caller exceeds a rate limit (HTTP 429). No rate limiter is wired in yet — this is
 * scaffolding for the per-user rate-limit interceptor seam (see {@code TODO.md} Phase 3.4).
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
