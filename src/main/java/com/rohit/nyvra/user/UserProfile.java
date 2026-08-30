package com.rohit.nyvra.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A nyvra user, keyed by the Keycloak {@code sub} claim. Never stores credentials.
 *
 * <p>NOTE: {@code email} is stored in plaintext in this skeleton. Per
 * {@code docs/engineering/DATABASE_DESIGN.md} it must become a field-level-encrypted (🔒) column with a
 * blind-index {@code email_hash} for lookup — follow-up before real data.
 */
@Entity
@Table(name = "user_profile")
public class UserProfile {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "keycloak_subject", nullable = false, unique = true, updatable = false)
    private String keycloakSubject;

    @Column(name = "email")
    private String email;

    @Column(name = "display_name")
    private String displayName;

    // @Column's columnDefinition only affects DDL generation, not schema *validation* — Hibernate
    // validates against the mapped JDBC type, which defaults to VARCHAR for a String. The migration
    // uses CHAR(3) per docs/engineering/DATABASE_DESIGN.md's currency-column convention, so the JDBC
    // type must be pinned to CHAR explicitly or ddl-auto=validate rejects it as a type mismatch.
    @Column(name = "base_currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String baseCurrency = "INR";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserProfile() {
        // for JPA
    }

    public UserProfile(String keycloakSubject, String email, String displayName) {
        this.id = UUID.randomUUID();
        this.keycloakSubject = keycloakSubject;
        this.email = email;
        this.displayName = displayName;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getKeycloakSubject() {
        return keycloakSubject;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
