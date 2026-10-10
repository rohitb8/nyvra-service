package com.rohit.nyvra.expense;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** User-scoped; pass a date range where possible so Postgres prunes partitions. */
public interface ExpenseRepository extends JpaRepository<Expense, UUID>, JpaSpecificationExecutor<Expense> {

    Page<Expense> findByUserIdAndDateBetweenOrderByDateDescIdDesc(
        UUID userId, LocalDate from, LocalDate to, Pageable pageable);

    List<Expense> findByParentExpenseIdAndDate(UUID parentExpenseId, LocalDate date);

    List<Expense> findByUserIdAndParentExpenseIdIn(UUID userId, Collection<UUID> parentExpenseIds);

    List<Expense> findByUserIdAndTransactionIdIn(UUID userId, Collection<UUID> transactionIds);
}
