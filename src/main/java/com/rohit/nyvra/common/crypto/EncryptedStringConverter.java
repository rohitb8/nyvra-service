package com.rohit.nyvra.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps a {@code String} attribute to an encrypted {@code BYTEA} column. Apply explicitly with
 * {@code @Convert(converter = EncryptedStringConverter.class)} on every 🔒 column — never auto-applied.
 *
 * <p>Instantiated by Hibernate through Spring's bean container, so the {@link FieldEncryptor} is
 * constructor-injected. Encrypted columns are never usable in {@code WHERE}/{@code ORDER BY}; pair them
 * with a {@link BlindIndexHasher} column when lookup is needed.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, byte[]> {

    private final FieldEncryptor encryptor;

    public EncryptedStringConverter(FieldEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public byte[] convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(byte[] dbData) {
        return dbData == null ? null : encryptor.decrypt(dbData);
    }
}
