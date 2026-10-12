package com.rohit.nyvra.common.idempotency;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.nyvra.common.exception.ApiError;
import com.rohit.nyvra.common.exception.ErrorCodes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Honours the optional {@code Idempotency-Key} header on {@code POST} requests (API_DESIGN §6).
 *
 * <p>For an authenticated {@code POST} carrying the header, a repeat with the same key and the same
 * request replays the stored 2xx response (plus {@code Idempotent-Replayed: true}); the same key with a
 * different request is {@code 409 IDEMPOTENCY_KEY_REUSED}; a repeat while the first is still running is
 * {@code 409 IDEMPOTENCY_REQUEST_IN_PROGRESS}. Only 2xx responses are stored — a failed attempt frees the
 * key so the client can fix the request and retry with it. Requests without the header, non-{@code POST}
 * requests and unauthenticated requests pass straight through.
 *
 * <p>Like the other cross-cutting filters it is instantiated directly (not a {@code @Component}) and
 * registered in {@code SecurityConfig} right after the authentication filters, because it needs the
 * resolved caller and runs before MVC dispatch, so errors are written here rather than by
 * {@code GlobalExceptionHandler}. Multipart bodies are not buffered; their hash covers method, URI,
 * content type and length instead.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    /** Request header carrying the client-generated key. */
    public static final String KEY_HEADER = "Idempotency-Key";

    /** Response header set to {@code true} when a stored response is replayed. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** Where the keys and stored responses live. */
    private final IdempotencyStore store;

    /** Writes the {@link ApiError} bodies this filter produces itself. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the filter.
     *
     * @param store        the Redis-backed key store
     * @param objectMapper mapper used to write error bodies
     */
    public IdempotencyFilter(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * Skips everything except authenticated {@code POST}s that carry the header.
     *
     * @param request the incoming request
     * @return {@code true} when the request must pass through untouched
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return !HttpMethod.POST.matches(request.getMethod())
            || request.getHeader(KEY_HEADER) == null
            || auth == null
            || auth instanceof AnonymousAuthenticationToken
            || !auth.isAuthenticated();
    }

    /**
     * Claims the key, runs the request and stores or releases the key depending on the outcome.
     *
     * @param request  the incoming request
     * @param response the response to write to
     * @param chain    the rest of the filter chain
     * @throws ServletException if the chain fails
     * @throws IOException      if reading or writing fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(KEY_HEADER).trim();
        if (!isUuid(key)) {
            writeError(request, response, HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST,
                "Idempotency-Key must be a UUID");
            return;
        }
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();

        HttpServletRequest effective = request;
        String requestHash;
        if (isMultipart(request)) {
            requestHash = sha256(request.getMethod(), request.getRequestURI(), request.getQueryString(),
                request.getContentType(), String.valueOf(request.getContentLengthLong()));
        } else {
            byte[] body = request.getInputStream().readAllBytes();
            effective = new CachedBodyRequest(request, body);
            requestHash = sha256(request.getMethod(), request.getRequestURI(), request.getQueryString(),
                new String(body, StandardCharsets.UTF_8));
        }

        IdempotencyStore.Claim claim = store.claim(userId, key, requestHash);
        switch (claim.outcome()) {
            case REPLAY -> replay(response, claim.response());
            case MISMATCH -> writeError(request, response, HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_KEY_REUSED,
                "This Idempotency-Key was already used for a different request");
            case IN_PROGRESS -> writeError(request, response, HttpStatus.CONFLICT,
                ErrorCodes.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                "A request with this Idempotency-Key is still being processed");
            case UNAVAILABLE -> chain.doFilter(effective, response);
            case CLAIMED -> process(effective, response, chain, userId, key, requestHash);
        }
    }

    /** Runs the request with a capturing response, then stores a 2xx result or releases the key. */
    private void process(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            String userId, String key, String requestHash) throws ServletException, IOException {
        ContentCachingResponseWrapper capture = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(request, capture);
            int status = capture.getStatus();
            if (status >= 200 && status < 300) {
                store.complete(userId, key, requestHash, new IdempotencyStore.StoredResponse(
                    status,
                    capture.getContentType(),
                    capture.getHeader(HttpHeaders.LOCATION),
                    new String(capture.getContentAsByteArray(), StandardCharsets.UTF_8)));
                stored = true;
            }
        } finally {
            if (!stored) {
                store.release(userId, key);
            }
            capture.copyBodyToResponse();
        }
    }

    /** Writes a stored response back to the client, flagged as a replay. */
    private void replay(HttpServletResponse response, IdempotencyStore.StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        if (stored.location() != null) {
            response.setHeader(HttpHeaders.LOCATION, stored.location());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        response.getOutputStream().write(stored.body().getBytes(StandardCharsets.UTF_8));
    }

    /** Writes an {@link ApiError} body, as the security entry points do. */
    private void writeError(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String code, String message) throws IOException {
        ApiError body = ApiError.of(
            status.value(), status.getReasonPhrase(), message, code, request.getRequestURI(), null,
            MDC.get("traceId"));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }

    /** Whether the request body is multipart (not safe to pre-read, see class Javadoc). */
    private static boolean isMultipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase().startsWith("multipart/");
    }

    /** Whether the value parses as a UUID. */
    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Hex SHA-256 over the parts, separated so that different splits never hash the same. */
    private static String sha256(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** A request whose body was already read, so downstream can read it again. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        /** The body bytes read up front. */
        private final byte[] body;

        /**
         * Wraps a request around its already-read body.
         *
         * @param request the original request
         * @param body    its body bytes
         */
        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        /**
         * Serves the cached body.
         *
         * @return a fresh stream over the body
         */
        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                /**
                 * Reads the next byte.
                 *
                 * @return the byte, or -1 at the end
                 */
                @Override
                public int read() {
                    return in.read();
                }

                /**
                 * Whether the whole body has been consumed.
                 *
                 * @return {@code true} at the end
                 */
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                /**
                 * Whether a read would not block.
                 *
                 * @return always {@code true}
                 */
                @Override
                public boolean isReady() {
                    return true;
                }

                /**
                 * Async reads are unsupported; the body is fully buffered.
                 *
                 * @param listener ignored
                 */
                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Buffered body; no async reads");
                }
            };
        }

        /**
         * Serves the cached body as a reader.
         *
         * @return a reader in the request's character encoding
         */
        @Override
        public java.io.BufferedReader getReader() {
            String encoding = getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8";
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                new ByteArrayInputStream(body), java.nio.charset.Charset.forName(encoding)));
        }
    }
}
