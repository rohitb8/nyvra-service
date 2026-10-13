package com.rohit.nyvra.common.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.rohit.nyvra.common.exception.BadRequestException;

/**
 * Opaque keyset position {@code (date, id)} for a newest-first feed (transactions, expenses), bound to the
 * filters it was issued with so a cursor can't be replayed against a different query.
 *
 * @param date accounting date of the last item on the previous page
 * @param id UUID of the last item on the previous page; tie-breaker within the same date
 */
public record DateIdCursor(LocalDate date, UUID id) {

    /**
     * Encodes this position as an opaque URL-safe Base64 token bound to the given filters.
     *
     * @param filterFingerprint canonical string of the filters of the current query
     * @return Base64 (no padding) of {@code date|id|fingerprint}
     */
    public String encode(String filterFingerprint) {
        String raw = date + "|" + id + "|" + fingerprint(filterFingerprint);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes and validates a cursor token produced by {@link #encode(String)}.
     *
     * @param cursor the opaque token received from the client
     * @param filterFingerprint canonical string of the filters of the current query
     * @return the decoded position
     * @throws BadRequestException if the token is malformed or was issued for different filters
     */
    public static DateIdCursor decode(String cursor, String filterFingerprint) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("shape");
            }
            if (!parts[2].equals(fingerprint(filterFingerprint))) {
                throw new BadRequestException("cursor does not match the filters of this request; start without a cursor");
            }
            return new DateIdCursor(LocalDate.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new BadRequestException("Invalid cursor");
        }
    }

    /**
     * Short stable digest (first 6 bytes of SHA-256, hex) of the filter string.
     *
     * @param filters canonical filter string
     * @return 12 hex characters
     */
    private static String fingerprint(String filters) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(filters.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
