package com.rohit.nyvra.common.idempotency;

import java.time.Duration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed record of {@code (userId, Idempotency-Key) -> (request hash, response)} used by
 * {@link IdempotencyFilter}.
 *
 * <p>A key is first <i>claimed</i> atomically ({@code SET NX}) with a short "in progress" TTL, then
 * overwritten with the finished response for the full retention window (24 h by default), or deleted
 * if the request did not succeed so the client can retry. Every Redis failure is swallowed and reported
 * as {@link Outcome#UNAVAILABLE}: the header is optional in v1, so an outage must not block creates.
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    /** Prefix that namespaces idempotency entries in Redis. */
    private static final String KEY_PREFIX = "nyvra:idem:";

    /** What {@link #claim} found for a key. */
    public enum Outcome {
        /** Nobody used the key before; the caller now owns it and must {@code complete} or {@code release}. */
        CLAIMED,
        /** The key was used before with the same request and a stored response is available. */
        REPLAY,
        /** The key was used before with a different request. */
        MISMATCH,
        /** The same request is still being processed. */
        IN_PROGRESS,
        /** Redis could not be reached; proceed without idempotency. */
        UNAVAILABLE
    }

    /**
     * Result of {@link #claim}.
     *
     * @param outcome  what was found
     * @param response the stored response; only set for {@link Outcome#REPLAY}
     */
    public record Claim(Outcome outcome, StoredResponse response) {
    }

    /**
     * A captured response, replayed verbatim on a retry.
     *
     * @param status      HTTP status
     * @param contentType {@code Content-Type} header, may be {@code null}
     * @param location    {@code Location} header, may be {@code null}
     * @param body        response body as UTF-8 text, may be empty
     */
    public record StoredResponse(int status, String contentType, String location, String body) {
    }

    /**
     * Persisted shape of one entry; {@code response} is {@code null} while the request is in flight.
     *
     * @param requestHash hash of the request that claimed the key
     * @param response    the finished response, or {@code null} while in progress
     */
    record Entry(String requestHash, StoredResponse response) {
    }

    /** Redis access. */
    private final StringRedisTemplate redis;

    /** Serialises entries to JSON. */
    private final ObjectMapper objectMapper;

    /** How long a finished response is kept. */
    private final Duration ttl;

    /** How long an in-flight claim blocks duplicates before it expires (covers a crashed node). */
    private final Duration inProgressTtl;

    /**
     * Creates the store.
     *
     * @param redis         Redis client
     * @param objectMapper  mapper for the stored entries
     * @param ttl           retention of finished responses ({@code nyvra.idempotency.ttl}, default 24 h)
     * @param inProgressTtl lifetime of an unfinished claim ({@code nyvra.idempotency.in-progress-ttl}, default 2 min)
     */
    public IdempotencyStore(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${nyvra.idempotency.ttl:PT24H}") Duration ttl,
            @Value("${nyvra.idempotency.in-progress-ttl:PT2M}") Duration inProgressTtl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.inProgressTtl = inProgressTtl;
    }

    /**
     * Tries to claim a key for a request, or reports why it cannot.
     *
     * @param userId      the caller (token subject)
     * @param key         the client's {@code Idempotency-Key}
     * @param requestHash hash of the request being made
     * @return the outcome, with the stored response for a replay
     */
    public Claim claim(String userId, String key, String requestHash) {
        try {
            String redisKey = redisKey(userId, key);
            String mine = objectMapper.writeValueAsString(new Entry(requestHash, null));
            // A second pass covers the entry expiring between the failed SET NX and the GET.
            for (int attempt = 0; attempt < 2; attempt++) {
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, mine, inProgressTtl))) {
                    return new Claim(Outcome.CLAIMED, null);
                }
                String existing = redis.opsForValue().get(redisKey);
                if (existing == null) {
                    continue;
                }
                Entry entry = objectMapper.readValue(existing, Entry.class);
                if (!entry.requestHash().equals(requestHash)) {
                    return new Claim(Outcome.MISMATCH, null);
                }
                return entry.response() == null
                    ? new Claim(Outcome.IN_PROGRESS, null)
                    : new Claim(Outcome.REPLAY, entry.response());
            }
            return new Claim(Outcome.IN_PROGRESS, null);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Idempotency store unavailable, continuing without it: {}", e.toString());
            return new Claim(Outcome.UNAVAILABLE, null);
        }
    }

    /**
     * Stores the finished response for a claimed key for the full retention window.
     *
     * @param userId      the caller (token subject)
     * @param key         the client's {@code Idempotency-Key}
     * @param requestHash hash of the request that claimed the key
     * @param response    the response to replay on retries
     */
    public void complete(String userId, String key, String requestHash, StoredResponse response) {
        try {
            String json = objectMapper.writeValueAsString(new Entry(requestHash, response));
            redis.opsForValue().set(redisKey(userId, key), json, ttl);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Could not store idempotent response: {}", e.toString());
        }
    }

    /**
     * Drops a claimed key so the client can retry (the request did not succeed).
     *
     * @param userId the caller (token subject)
     * @param key    the client's {@code Idempotency-Key}
     */
    public void release(String userId, String key) {
        try {
            redis.delete(redisKey(userId, key));
        } catch (RuntimeException e) {
            log.warn("Could not release idempotency key: {}", e.toString());
        }
    }

    /** Builds the per-user Redis key, so one user's keys can never collide with another's. */
    private static String redisKey(String userId, String key) {
        return KEY_PREFIX + userId + ":" + key;
    }
}
