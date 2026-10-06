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

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public BlindIndexHasher(byte[] key) {
        this.key = new SecretKeySpec(key, ALGORITHM);
    }

    public static BlindIndexHasher from(CryptoProperties properties) {
        return new BlindIndexHasher(CryptoKeys.decode(properties.blindIndexKey(), "NYVRA_BLIND_INDEX_KEY"));
    }

    /** Lower-case hex HMAC of the UTF-8 value. Callers normalise (trim, case-fold) before hashing. */
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
