package com.rohit.nyvra.income;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for {@link IncomeEntry}. Reads that serve the API take the user id so another user's
 * rows are never visible; the {@code Specification} support powers the filtered, sorted entry list.
 */
public interface IncomeEntryRepository
    extends JpaRepository<IncomeEntry, UUID>, JpaSpecificationExecutor<IncomeEntry> {

    /**
     * Finds an entry owned by the given user.
     *
     * @param id the entry id
     * @param userId the owning user
     * @return the entry, or empty when it does not exist or belongs to someone else
     */
    Optional<IncomeEntry> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Pages a user's entries, newest period first.
     *
     * @param userId the owning user
     * @param pageable paging request
     * @return one page of entries
     */
    Page<IncomeEntry> findByUserIdOrderByPeriodStartDesc(UUID userId, Pageable pageable);

    /**
     * Pages the entries of one of a user's sources, newest period first.
     *
     * @param sourceId the source
     * @param userId the owning user
     * @param pageable paging request
     * @return one page of entries
     */
    Page<IncomeEntry> findBySourceIdAndUserIdOrderByPeriodStartDesc(UUID sourceId, UUID userId, Pageable pageable);

    /**
     * Whether the source has at least one entry, which decides between deleting and deactivating it.
     *
     * @param sourceId the source
     * @return {@code true} if any entry references it
     */
    boolean existsBySourceId(UUID sourceId);

    /**
     * Whether another entry of the source covers any day in {@code [start, end]} (both inclusive).
     *
     * @param sourceId  the source whose entries are compared
     * @param excludeId an entry id to ignore (the entry being edited), or the nil UUID to exclude nothing
     * @param start     first day of the candidate period
     * @param end       last day of the candidate period
     * @return {@code true} if a different entry overlaps the period
     */
    @Query("""
        select count(e) > 0 from IncomeEntry e
        where e.sourceId = :sourceId and e.id <> :excludeId
          and e.periodStart <= :end and e.periodEnd >= :start""")
    boolean existsOverlapping(@Param("sourceId") UUID sourceId, @Param("excludeId") UUID excludeId,
                              @Param("start") LocalDate start, @Param("end") LocalDate end);
}
