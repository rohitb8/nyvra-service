package com.rohit.nyvra.ingestion;

import java.util.UUID;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Base for the Ingestion tables that have no {@code created_at}/{@code updated_at} pair (so they cannot
 * extend {@code AbstractEntity}): an app-generated UUID v7 id and the {@link Persistable} new-entity flag.
 */
@MappedSuperclass
abstract class IngestionEntity implements Persistable<UUID> {

    /** Time-ordered primary key, assigned at construction. */
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** True until the entity has been persisted or loaded, so Spring Data issues a plain insert. */
    @Transient
    private boolean isNew = true;

    /** Assigns a fresh UUID v7 id; also run by JPA, which then overwrites it with the stored value. */
    protected IngestionEntity() {
        this.id = UuidCreator.getTimeOrderedEpoch();
    }

    /** Marks the entity as no longer new once it is stored or loaded. */
    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    /**
     * Returns the primary key.
     *
     * @return the id
     */
    @Override
    public UUID getId() {
        return id;
    }

    /**
     * Tells Spring Data whether to insert rather than merge.
     *
     * @return true until first stored or loaded
     */
    @Override
    public boolean isNew() {
        return isNew;
    }
}
