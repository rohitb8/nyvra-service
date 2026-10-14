package com.rohit.nyvra.common.crypto;

import java.util.Base64;

/** Decoding and validation of the base64 key material used by the crypto beans. */
final class CryptoKeys {

    /** Required key length in bytes (AES-256 / HMAC-SHA256 key size). */
    static final int KEY_LENGTH_BYTES = 32;

    /** Utility class; not instantiable. */
    private CryptoKeys() {
    }

    /**
     * Decodes a base64 key and fails fast (at startup) on a missing, placeholder or wrong-length value.
     *
     *
     * @param base64 the base64 text from configuration, may be {@code null}
     * @param name environment variable name, used only in error messages
     * @return the 32 raw key bytes
     * @throws IllegalStateException if the value is blank, not base64, or not 32 bytes long
     */
    static byte[] decode(String base64, String name) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(name + " is not set — see docs/operations/ENVIRONMENTS.md §6");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " is not valid base64 — generate with `openssl rand -base64 32`");
        }
        if (key.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                name + " must decode to " + KEY_LENGTH_BYTES + " bytes, got " + key.length);
        }
        return key;
    }
}
