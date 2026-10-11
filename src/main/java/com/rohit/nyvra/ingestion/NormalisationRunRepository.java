package com.rohit.nyvra.ingestion;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for {@link NormalisationRun}. */
public interface NormalisationRunRepository extends JpaRepository<NormalisationRun, UUID> {

    /**
     * Finds the most recent pass for a fetch.
     *
     * @param fetchSessionId the fetch
     * @return the latest run, or empty when none has run
     */
    Optional<NormalisationRun> findFirstByFetchSessionIdOrderByCreatedAtDesc(UUID fetchSessionId);
}
