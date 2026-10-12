package com.rohit.nyvra.expense;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** User-scoped; pass a date range where possible so Postgres prunes partitions. */
public interface ExpenseRepository extends JpaRepository<Expense, UUID>, JpaSpecificationExecutor<Expense> {

    /**
     * One page of the user's expenses in a date range, newest first.
     *
     * @param userId   the owner
     * @param from     first date, inclusive
     * @param to       last date, inclusive
     * @param pageable the page to read
     * @return the page
     */
    Page<Expense> findByUserIdAndDateBetweenOrderByDateDescIdDesc(
        UUID userId, LocalDate from, LocalDate to, Pageable pageable);

    /**
     * The split parts of one parent.
     *
     * @param parentExpenseId the parent's id
     * @param date            the parent's date (parts share it)
     * @return the parts
     */
    List<Expense> findByParentExpenseIdAndDate(UUID parentExpenseId, LocalDate date);

    /**
     * The split parts of several parents.
     *
     * @param userId           the owner
     * @param parentExpenseIds the parents' ids
     * @return the parts of all of them
     */
    List<Expense> findByUserIdAndParentExpenseIdIn(UUID userId, Collection<UUID> parentExpenseIds);

    /**
     * The expenses derived from the given Accounts transactions.
     *
     * @param userId         the owner
     * @param transactionIds the transaction ids
     * @return the matching expenses
     */
    List<Expense> findByUserIdAndTransactionIdIn(UUID userId, Collection<UUID> transactionIds);

    /**
     * Every expense (parents and split parts) the user has in a date range, for monthly aggregation.
     *
     * @param userId the owner
     * @param from   first date, inclusive
     * @param to     last date, inclusive
     * @return the expenses
     */
    List<Expense> findByUserIdAndDateBetween(UUID userId, LocalDate from, LocalDate to);

    /**
     * Whether any of the user's expenses uses the category, as category or subcategory.
     *
     * @param userId     the owner
     * @param categoryId the category to look for
     * @return true if at least one expense points at it
     */
    @Query("""
        SELECT COUNT(e) > 0 FROM Expense e
        WHERE e.userId = :userId AND (e.categoryId = :categoryId OR e.subcategoryId = :categoryId)
        """)
    boolean existsByCategory(@Param("userId") UUID userId, @Param("categoryId") UUID categoryId);

    /**
     * Top-level expenses a rule back-fill may re-categorise: not split parts, with a merchant, and whose category
     * the system (not the user) chose. Ordered so that paging stays stable while rows are updated.
     *
     * @param userId   the owner
     * @param source   the category source to match, normally {@link CategorySource#AUTO}
     * @param pageable the slice to read
     * @return a slice of candidates
     */
    @Query("""
        SELECT e FROM Expense e
        WHERE e.userId = :userId AND e.categorySource = :source
          AND e.parentExpenseId IS NULL AND e.merchant IS NOT NULL
        ORDER BY e.date DESC, e.id DESC
        """)
    Slice<Expense> findBackfillCandidates(@Param("userId") UUID userId, @Param("source") CategorySource source,
                                          Pageable pageable);
}
