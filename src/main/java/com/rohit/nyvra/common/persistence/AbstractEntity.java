package com.rohit.nyvra.common.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import com.github.f4b6a3.uuid.UuidCreator;

/**
 * Base for every entity: an app-generated UUID v7 {@code id} plus {@code created_at}/{@code updated_at}
 * (see {@code database/decisions.md} and {@code docs/engineering/DATABASE_DESIGN.md} "Global conventions").
 *
 * <p>Implements {@link Persistable} so Spring Data issues a plain {@code persist} for a freshly
 * constructed entity instead of a select-then-merge — the id is always assigned up front, so Spring
 * Data could not otherwise tell a new entity from a detached one.
 */
@MappedSuperclass
public abstract class AbstractEntity implements Persistable<UUID> {

    /** Primary key, UUID v7 generated in the application. */
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** UTC instant the row was first persisted; never updated. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** UTC instant of the last persist or update. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Transient flag telling Spring Data whether this instance is unsaved; cleared after load or persist. */
    @Transient
    private boolean isNew = true;

    /** Assigns a fresh time-ordered UUID v7 id. */
    protected AbstractEntity() {
        // Time-ordered (v7) so B-tree inserts stay append-mostly. JPA's own instantiation also runs
        // this; Hibernate then overwrites the id with the loaded value.
        this.id = UuidCreator.getTimeOrderedEpoch();
    }

    /** Stamps both timestamps just before the first insert. */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Refreshes the update timestamp just before an update. */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Marks the entity as existing once loaded or persisted. */
    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    /** Returns the primary key. */
    @Override
    public UUID getId() {
        return id;
    }

    /** Whether the entity is still unsaved: {@code true} until it has been loaded or persisted. */
    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Returns the creation instant (UTC). */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Returns the last-update instant (UTC). */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
