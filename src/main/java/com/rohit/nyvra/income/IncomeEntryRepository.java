package com.rohit.nyvra.income;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncomeEntryRepository extends JpaRepository<IncomeEntry, UUID> {

    Optional<IncomeEntry> findByIdAndUserId(UUID id, UUID userId);

    Page<IncomeEntry> findByUserIdOrderByPeriodStartDesc(UUID userId, Pageable pageable);

    Page<IncomeEntry> findBySourceIdAndUserIdOrderByPeriodStartDesc(UUID sourceId, UUID userId, Pageable pageable);
}
