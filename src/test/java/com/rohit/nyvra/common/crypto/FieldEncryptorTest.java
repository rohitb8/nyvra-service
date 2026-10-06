package com.rohit.nyvra.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

class FieldEncryptorTest {

    private static byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        FieldEncryptor encryptor = new FieldEncryptor(key(), null);

        byte[] stored = encryptor.encrypt("UPI/ACME STORES/ref 1234");

        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("ACME");
        assertThat(encryptor.decrypt(stored)).isEqualTo("UPI/ACME STORES/ref 1234");
    }

    @Test
    void usesAFreshIvForEveryValue() {
        FieldEncryptor encryptor = new FieldEncryptor(key(), null);

        assertThat(encryptor.encrypt("same")).isNotEqualTo(encryptor.encrypt("same"));
    }

    @Test
    void decryptsValuesWrittenUnderThePreviousKeyDuringRotation() {
        byte[] oldKey = key();
        byte[] stored = new FieldEncryptor(oldKey, null).encrypt("written before rotation");

        FieldEncryptor rotated = new FieldEncryptor(key(), oldKey);

        assertThat(rotated.decrypt(stored)).isEqualTo("written before rotation");
    }

    @Test
    void rejectsTamperedCiphertext() {
        FieldEncryptor encryptor = new FieldEncryptor(key(), null);
        byte[] stored = encryptor.encrypt("value");
        stored[stored.length - 1] ^= 1;

        assertThatThrownBy(() -> encryptor.decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastOnAPlaceholderKey() {
        CryptoProperties placeholder = new CryptoProperties("CHANGE_ME_base64_32_bytes", null, null);

        assertThatThrownBy(() -> FieldEncryptor.from(placeholder))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NYVRA_FIELD_ENCRYPTION_KEY");
    }
}
