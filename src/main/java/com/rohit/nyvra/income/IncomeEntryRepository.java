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

public interface IncomeEntryRepository
    extends JpaRepository<IncomeEntry, UUID>, JpaSpecificationExecutor<IncomeEntry> {

    Optional<IncomeEntry> findByIdAndUserId(UUID id, UUID userId);

    Page<IncomeEntry> findByUserIdOrderByPeriodStartDesc(UUID userId, Pageable pageable);

    Page<IncomeEntry> findBySourceIdAndUserIdOrderByPeriodStartDesc(UUID sourceId, UUID userId, Pageable pageable);

    boolean existsBySourceId(UUID sourceId);

    /** Whether another entry of the source covers any day in {@code [start, end]} (both inclusive). */
    @Query("""
        select count(e) > 0 from IncomeEntry e
        where e.sourceId = :sourceId and e.id <> :excludeId
          and e.periodStart <= :end and e.periodEnd >= :start""")
    boolean existsOverlapping(@Param("sourceId") UUID sourceId, @Param("excludeId") UUID excludeId,
                              @Param("start") LocalDate start, @Param("end") LocalDate end);
}
