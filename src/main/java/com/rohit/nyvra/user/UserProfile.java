package com.rohit.nyvra.user;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rohit.nyvra.common.crypto.EncryptedStringConverter;
import com.rohit.nyvra.common.persistence.AbstractEntity;

/**
 * A nyvra user, keyed by the Keycloak {@code sub} claim. Never stores credentials.
 *
 * <p>{@code email} is a field-encrypted (🔒) column with a blind-index {@code email_hash} for lookup
 * ({@code database/decisions.md} §1). The legacy plaintext {@code email} column is deliberately
 * unmapped: it is kept only until the backfill is verified everywhere, then dropped (contract step).
 */
@Entity
@Table(name = "user_profile")
public class UserProfile extends AbstractEntity {

    @Column(name = "keycloak_subject", nullable = false, unique = true, updatable = false)
    private String keycloakSubject;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "email_encrypted")
    private String email;

    @Column(name = "email_hash", unique = true)
    private String emailHash;

    @Column(name = "display_name")
    private String displayName;

    // @Column's columnDefinition only affects DDL generation, not schema *validation* — Hibernate
    // validates against the mapped JDBC type, which defaults to VARCHAR for a String. The migration
    // uses CHAR(3) per docs/engineering/DATABASE_DESIGN.md's currency-column convention, so the JDBC
    // type must be pinned to CHAR explicitly or ddl-auto=validate rejects it as a type mismatch.
    @Column(name = "base_currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String baseCurrency = "INR";

    protected UserProfile() {
        // for JPA
    }

    public UserProfile(String keycloakSubject, String email, String emailHash, String displayName) {
        this.keycloakSubject = keycloakSubject;
        this.email = email;
        this.emailHash = emailHash;
        this.displayName = displayName;
    }

    public String getKeycloakSubject() {
        return keycloakSubject;
    }

    public String getEmail() {
        return email;
    }

    public String getEmailHash() {
        return emailHash;
    }

    /** Email and its blind index always change together. */
    public void changeEmail(String email, String emailHash) {
        this.email = email;
        this.emailHash = emailHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }
}
