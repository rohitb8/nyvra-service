package com.rohit.nyvra.ingestion;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for {@link AggregatorConsent}; every read that serves the API is user-scoped. */
public interface AggregatorConsentRepository extends JpaRepository<AggregatorConsent, UUID> {

    /**
     * Finds a consent owned by the given user.
     *
     * @param id     the consent id
     * @param userId the owning user
     * @return the consent, or empty when missing or owned by someone else
     */
    Optional<AggregatorConsent> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Pages a user's consents.
     *
     * @param userId   the owning user
     * @param pageable page and sort
     * @return the page
     */
    Page<AggregatorConsent> findByUserId(UUID userId, Pageable pageable);

    /**
     * Pages a user's consents in the given states.
     *
     * @param userId   the owning user
     * @param statuses the wanted states
     * @param pageable page and sort
     * @return the page
     */
    Page<AggregatorConsent> findByUserIdAndStatusIn(UUID userId, Collection<ConsentStatus> statuses, Pageable pageable);

    /**
     * Lists every consent of a user, used to scope fetch-session queries.
     *
     * @param userId the owning user
     * @return the user's consents
     */
    List<AggregatorConsent> findByUserId(UUID userId);

    /**
     * Finds consents in the given states whose expiry has passed, for the expiry job.
     *
     * @param statuses the states that can still expire
     * @param before   the instant expiries are compared with
     * @param pageable how many to read
     * @return a page of lapsed consents, oldest expiry first
     */
    @Query("""
        SELECT c FROM AggregatorConsent c
        WHERE c.status IN :statuses AND c.expiresAt IS NOT NULL AND c.expiresAt < :before
        ORDER BY c.expiresAt
        """)
    List<AggregatorConsent> findLapsed(@Param("statuses") Collection<ConsentStatus> statuses,
                                       @Param("before") Instant before, Pageable pageable);
}
