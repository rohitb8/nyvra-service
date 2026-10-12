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
 * <p>{@code email} is a field-encrypted (🔒) column ({@code email_encrypted}) with a blind-index
 * {@code email_hash} for lookup ({@code database/decisions.md} §1). No plaintext email column exists
 * any more (dropped in V28).
 */
@Entity
@Table(name = "user_profile")
public class UserProfile extends AbstractEntity {

    /** Keycloak {@code sub} claim; the immutable external identity of this user. */
    @Column(name = "keycloak_subject", nullable = false, unique = true, updatable = false)
    private String keycloakSubject;

    /** The user's email, transparently encrypted at rest; {@code null} when the token carried none. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "email_encrypted")
    private String email;

    /** Blind index (HMAC-SHA256) of the normalised email, used for equality lookups. */
    @Column(name = "email_hash", unique = true)
    private String emailHash;

    /** Display name from the token's {@code name} claim; may be {@code null}. */
    @Column(name = "display_name")
    private String displayName;

    // @Column's columnDefinition only affects DDL generation, not schema *validation* — Hibernate
    // validates against the mapped JDBC type, which defaults to VARCHAR for a String. The migration
    // uses CHAR(3) per docs/engineering/DATABASE_DESIGN.md's currency-column convention, so the JDBC
    // type must be pinned to CHAR explicitly or ddl-auto=validate rejects it as a type mismatch.
    /** ISO-4217 currency all of the user's totals are reported in; defaults to {@code INR}. */
    @Column(name = "base_currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String baseCurrency = "INR";

    /** Constructor for JPA only. */
    protected UserProfile() {
        // for JPA
    }

    /**
     * Creates a new profile.
     *
     * @param keycloakSubject the Keycloak {@code sub} claim
     * @param email           the plaintext email (encrypted on persist), or {@code null}
     * @param emailHash       the blind index of {@code email} from {@link EmailBlindIndex}, or {@code null}
     * @param displayName     the display name, or {@code null}
     */
    public UserProfile(String keycloakSubject, String email, String emailHash, String displayName) {
        this.keycloakSubject = keycloakSubject;
        this.email = email;
        this.emailHash = emailHash;
        this.displayName = displayName;
    }

    /** @return the Keycloak {@code sub} claim */
    public String getKeycloakSubject() {
        return keycloakSubject;
    }

    /** @return the decrypted email, or {@code null} if none is stored */
    public String getEmail() {
        return email;
    }

    /** @return the email blind index, or {@code null} if no email is stored */
    public String getEmailHash() {
        return emailHash;
    }

    /**
     * Replaces the email; the email and its blind index always change together.
     *
     * @param email     the new plaintext email, or {@code null}
     * @param emailHash the blind index of {@code email}, or {@code null}
     */
    public void changeEmail(String email, String emailHash) {
        this.email = email;
        this.emailHash = emailHash;
    }

    /** @return the display name, or {@code null} */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Sets the display name.
     *
     * @param displayName the new display name, or {@code null}
     */
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /** @return the ISO-4217 base currency code */
    public String getBaseCurrency() {
        return baseCurrency;
    }
}
