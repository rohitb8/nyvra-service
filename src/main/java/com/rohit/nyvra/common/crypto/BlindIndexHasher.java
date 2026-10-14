package com.rohit.nyvra.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed HMAC-SHA256 for equality lookups (and dedup keys) over values that are otherwise stored only
 * encrypted. Deterministic by design, so it does not rotate like the field-encryption key — see
 * {@code docs/operations/ENVIRONMENTS.md} §6.3.
 */
public class BlindIndexHasher {

    /** JCA name of the MAC algorithm. */
    private static final String ALGORITHM = "HmacSHA256";

    /** HMAC key built from the configured blind-index key bytes. */
    private final SecretKeySpec key;

    /**
     * Creates a hasher for the given key.
     *
     * @param key raw HMAC key bytes (32 bytes when built via {@link #from(CryptoProperties)})
     */
    public BlindIndexHasher(byte[] key) {
        this.key = new SecretKeySpec(key, ALGORITHM);
    }

    /**
     * Builds a hasher from the bound crypto properties.
     *
     * @param properties bound {@code nyvra.crypto} properties
     * @return a hasher using the decoded blind-index key
     * @throws IllegalStateException if the key is missing, not base64, or not 32 bytes
     */
    public static BlindIndexHasher from(CryptoProperties properties) {
        return new BlindIndexHasher(CryptoKeys.decode(properties.blindIndexKey(), "NYVRA_BLIND_INDEX_KEY"));
    }

    /**
     * Lower-case hex HMAC of the UTF-8 value. Callers normalise (trim, case-fold) before hashing.
     *
     *
     * @param value the already-normalised plaintext
     * @return 64-character lower-case hex digest
     * @throws IllegalStateException if the HMAC provider is unavailable
     */
    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Blind index hashing failed", e);
        }
    }
}
