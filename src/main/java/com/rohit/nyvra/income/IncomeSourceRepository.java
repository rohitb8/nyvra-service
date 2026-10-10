package com.rohit.nyvra.income;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Persistence access for {@link IncomeSource}. Reads that serve the API take the user id so another
 * user's rows are never visible; the {@code Specification} support powers the filtered, sorted source list.
 */
public interface IncomeSourceRepository
    extends JpaRepository<IncomeSource, UUID>, JpaSpecificationExecutor<IncomeSource> {

    /**
     * Finds a source owned by the given user.
     *
     * @param id the source id
     * @param userId the owning user
     * @return the source, or empty when it does not exist or belongs to someone else
     */
    Optional<IncomeSource> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Lists a user's active sources alphabetically by name.
     *
     * @param userId the owning user
     * @return the active sources
     */
    List<IncomeSource> findByUserIdAndActiveTrueOrderByNameAsc(UUID userId);

    /**
     * Loads several sources at once, so a page of entries can show source names without one query per row.
     * Not user-scoped: callers pass ids taken from the user's own entries.
     *
     * @param ids the source ids
     * @return the sources that exist
     */
    List<IncomeSource> findByIdIn(Collection<UUID> ids);
}
