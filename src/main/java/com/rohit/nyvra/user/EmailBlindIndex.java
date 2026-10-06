package com.rohit.nyvra.user;

import java.util.Locale;

import com.rohit.nyvra.common.crypto.BlindIndexHasher;

/** Single place that normalises an email before blind-indexing, so writes and lookups agree. */
final class EmailBlindIndex {

    private EmailBlindIndex() {
    }

    /** Blind index of the trimmed, lower-cased email; {@code null} for a missing or blank email. */
    static String of(BlindIndexHasher hasher, String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return hasher.hash(email.trim().toLowerCase(Locale.ROOT));
    }
}
