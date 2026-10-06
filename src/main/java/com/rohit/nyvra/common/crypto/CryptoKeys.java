package com.rohit.nyvra.common.crypto;

import java.util.Base64;

final class CryptoKeys {

    static final int KEY_LENGTH_BYTES = 32;

    private CryptoKeys() {
    }

    /** Decodes a base64 key and fails fast (at startup) on a missing, placeholder or wrong-length value. */
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
