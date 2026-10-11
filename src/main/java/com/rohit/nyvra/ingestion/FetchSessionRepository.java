package com.rohit.nyvra.ingestion;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Persistence access for {@link FetchSession}; filtered, user-scoped lists use specifications. */
public interface FetchSessionRepository
    extends JpaRepository<FetchSession, UUID>, JpaSpecificationExecutor<FetchSession> {

    /**
     * Finds the session already created for a consent and data range.
     *
     * @param dedupKey the idempotency hash
     * @return the session, or empty when none exists
     */
    Optional<FetchSession> findByDedupKey(String dedupKey);
}
