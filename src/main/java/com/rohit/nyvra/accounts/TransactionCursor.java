package com.rohit.nyvra.accounts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.rohit.nyvra.common.exception.BadRequestException;

/**
 * Opaque keyset position {@code (valueDate, id)} for the newest-first transaction feed, bound to the
 * filters it was issued with so a cursor can't be replayed against a different query.
 */
record TransactionCursor(LocalDate valueDate, UUID id) {

    String encode(String filterFingerprint) {
        String raw = valueDate + "|" + id + "|" + fingerprint(filterFingerprint);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static TransactionCursor decode(String cursor, String filterFingerprint) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("shape");
            }
            if (!parts[2].equals(fingerprint(filterFingerprint))) {
                throw new BadRequestException("cursor does not match the filters of this request; start without a cursor");
            }
            return new TransactionCursor(LocalDate.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new BadRequestException("Invalid cursor");
        }
    }

    private static String fingerprint(String filters) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(filters.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
