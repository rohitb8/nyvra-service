package com.rohit.nyvra.networth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ManualAssetLiability}; every query is scoped to one user and hides soft-deleted rows. */
public interface ManualAssetLiabilityRepository extends JpaRepository<ManualAssetLiability, UUID> {

    /**
     * Finds one live entry of a user.
     *
     * @param id     the entry id
     * @param userId the owner
     * @return the entry, or empty if it does not exist, is deleted or belongs to someone else
     */
    Optional<ManualAssetLiability> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    /**
     * Lists a user's live entries.
     *
     * @param userId the owner
     * @return the entries, oldest first
     */
    List<ManualAssetLiability> findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID userId);

    /**
     * Pages a user's live entries.
     *
     * @param userId   the owner
     * @param pageable page and sort
     * @return the page
     */
    Page<ManualAssetLiability> findByUserIdAndDeletedAtIsNull(UUID userId, Pageable pageable);

    /**
     * Pages a user's live entries of one kind.
     *
     * @param userId   the owner
     * @param kind     asset or liability
     * @param pageable page and sort
     * @return the page
     */
    Page<ManualAssetLiability> findByUserIdAndKindAndDeletedAtIsNull(UUID userId, ManualItemKind kind,
                                                                     Pageable pageable);
}
