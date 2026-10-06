package com.rohit.nyvra.expense;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpendingHabitSnapshotRepository extends JpaRepository<SpendingHabitSnapshot, UUID> {

    /** @param periodMonth the first day of the month */
    Optional<SpendingHabitSnapshot> findByUserIdAndPeriodMonth(UUID userId, LocalDate periodMonth);
}
