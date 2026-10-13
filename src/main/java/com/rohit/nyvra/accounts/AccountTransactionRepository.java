package com.rohit.nyvra.accounts;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Every query is user-scoped and should carry a {@code valueDate} range where it can, so Postgres
 * prunes partitions instead of scanning every month.
 */
public interface AccountTransactionRepository
        extends JpaRepository<AccountTransaction, UUID>, JpaSpecificationExecutor<AccountTransaction> {

    /**
     * All of a user's lines, newest first.
     *
     * @param userId owner
     * @param pageable page request
     * @return a page of lines
     */
    Page<AccountTransaction> findByUserIdOrderByValueDateDescIdDesc(UUID userId, Pageable pageable);

    /**
     * A user's lines in a value-date range, newest first.
     *
     * @param userId owner
     * @param from inclusive start date
     * @param to inclusive end date
     * @param pageable page request
     * @return a page of lines
     */
    Page<AccountTransaction> findByUserIdAndValueDateBetweenOrderByValueDateDescIdDesc(
        UUID userId, LocalDate from, LocalDate to, Pageable pageable);

    /**
     * One account's lines for a user, newest first.
     *
     * @param accountId account id
     * @param userId owner
     * @param pageable page request
     * @return a page of lines
     */
    Page<AccountTransaction> findByAccountIdAndUserIdOrderByValueDateDescIdDesc(
        UUID accountId, UUID userId, Pageable pageable);

    /**
     * Duplicate check for imports.
     *
     * @param dedupKey deduplication key
     * @param valueDate value date (keeps the partition prune)
     * @return true if such a line exists
     */
    boolean existsByDedupKeyAndValueDate(String dedupKey, LocalDate valueDate);
}
