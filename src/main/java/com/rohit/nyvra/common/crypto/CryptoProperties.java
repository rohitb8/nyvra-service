package com.rohit.nyvra.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Base64-encoded 32-byte keys for field-level encryption and blind indexes. Bound from
 * {@code NYVRA_FIELD_ENCRYPTION_KEY} (+ {@code _PREVIOUS} during rotation) and {@code NYVRA_BLIND_INDEX_KEY}
 * — never from a committed file. See {@code docs/operations/ENVIRONMENTS.md} §6.
 *
 * @param fieldEncryptionKey base64 32-byte AES key used for all new encryption
 * @param fieldEncryptionKeyPrevious optional base64 32-byte previous key, used only to decrypt during rotation
 * @param blindIndexKey base64 32-byte HMAC key for blind indexes
 */
@ConfigurationProperties(prefix = "nyvra.crypto")
public record CryptoProperties(
    String fieldEncryptionKey,
    String fieldEncryptionKeyPrevious,
    String blindIndexKey
) {
}
