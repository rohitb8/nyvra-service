package com.rohit.nyvra.ingestion;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
