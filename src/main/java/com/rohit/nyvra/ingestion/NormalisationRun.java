package com.rohit.nyvra.ingestion;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One pass turning a fetch's raw records into normalised events. Deterministic and re-runnable: downstream
 * dedup keys stop a re-run from double-counting.
 */
@Entity
@Table(name = "normalisation_run")
public class NormalisationRun extends IngestionEntity {

    /** Status recorded for a pass that finished. */
    public static final String COMPLETED = "COMPLETED";

    /** The fetch whose records were normalised. */
    @Column(name = "fetch_session_id", nullable = false, updatable = false)
    private UUID fetchSessionId;

    /** Number of normalised events published by this pass. */
    @Column(name = "produced_events")
    private Integer producedEvents;

    /** Counts of records or items that could not be mapped, keyed by reason. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unmapped")
    private Map<String, Integer> unmapped;

    /** Outcome of the pass. */
    @Column(name = "status")
    private String status;

    /** When the pass ran. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA; not for application use. */
    protected NormalisationRun() {
        // for JPA
    }

    /**
     * Records a finished pass.
     *
     * @param fetchSessionId the fetch that was normalised, required
     * @param producedEvents events published, zero or more
     * @param unmapped       counts of unmappable items by reason
     */
    public NormalisationRun(UUID fetchSessionId, int producedEvents, Map<String, Integer> unmapped) {
        this.fetchSessionId = Objects.requireNonNull(fetchSessionId, "fetchSessionId");
        this.producedEvents = producedEvents;
        this.unmapped = unmapped;
        this.status = COMPLETED;
    }

    /** Stamps the run time just before the first insert. */
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    /** @return the fetch id */
    public UUID getFetchSessionId() {
        return fetchSessionId;
    }

    /** @return events published by this pass */
    public Integer getProducedEvents() {
        return producedEvents;
    }

    /** @return unmappable item counts by reason */
    public Map<String, Integer> getUnmapped() {
        return unmapped;
    }

    /** @return the outcome */
    public String getStatus() {
        return status;
    }

    /** @return when the pass ran */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
