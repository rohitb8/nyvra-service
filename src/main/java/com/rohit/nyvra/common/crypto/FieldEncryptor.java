package com.rohit.nyvra.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption for 🔒 columns ({@code docs/engineering/DATABASE_DESIGN.md} "Field-level
 * encryption"). Stored layout: {@code [1-byte format version][12-byte IV][ciphertext + 16-byte tag]}.
 *
 * <p>Always encrypts with the current key; decrypts with the current key, then the previous one — that is
 * what makes the dual-key rotation in {@code docs/operations/ENVIRONMENTS.md} §6.3 zero-downtime.
 */
public class FieldEncryptor {

    private static final byte FORMAT_V1 = 1;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey currentKey;
    private final List<SecretKey> decryptionKeys;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(byte[] currentKey, byte[] previousKey) {
        this.currentKey = new SecretKeySpec(currentKey, "AES");
        List<SecretKey> keys = new ArrayList<>();
        keys.add(this.currentKey);
        if (previousKey != null) {
            keys.add(new SecretKeySpec(previousKey, "AES"));
        }
        this.decryptionKeys = List.copyOf(keys);
    }

    public static FieldEncryptor from(CryptoProperties properties) {
        String previous = properties.fieldEncryptionKeyPrevious();
        return new FieldEncryptor(
            CryptoKeys.decode(properties.fieldEncryptionKey(), "NYVRA_FIELD_ENCRYPTION_KEY"),
            previous == null || previous.isBlank()
                ? null
                : CryptoKeys.decode(previous, "NYVRA_FIELD_ENCRYPTION_KEY_PREVIOUS"));
    }

    public byte[] encrypt(String plaintext) {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_LENGTH_BYTES + ciphertext.length)
                .put(FORMAT_V1)
                .put(iv)
                .put(ciphertext)
                .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    public String decrypt(byte[] stored) {
        if (stored.length < 1 + IV_LENGTH_BYTES || stored[0] != FORMAT_V1) {
            throw new IllegalStateException("Unrecognised encrypted field format");
        }
        GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BITS, stored, 1, IV_LENGTH_BYTES);
        int offset = 1 + IV_LENGTH_BYTES;
        for (SecretKey key : decryptionKeys) {
            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key, spec);
                byte[] plaintext = cipher.doFinal(stored, offset, stored.length - offset);
                return new String(plaintext, StandardCharsets.UTF_8);
            } catch (AEADBadTagException wrongKeyOrTampered) {
                // try the next key
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Field decryption failed", e);
            }
        }
        // Deliberately no detail: neither key authenticated the value (wrong key, or tampered data).
        throw new IllegalStateException("Field decryption failed: no configured key authenticates the value");
    }
}
