package com.rohit.nyvra.accounts;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every query is user-scoped and should carry a {@code valueDate} range where it can, so Postgres
 * prunes partitions instead of scanning every month.
 */
public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, UUID> {

    Page<AccountTransaction> findByUserIdOrderByValueDateDescIdDesc(UUID userId, Pageable pageable);

    Page<AccountTransaction> findByUserIdAndValueDateBetweenOrderByValueDateDescIdDesc(
        UUID userId, LocalDate from, LocalDate to, Pageable pageable);

    Page<AccountTransaction> findByAccountIdAndUserIdOrderByValueDateDescIdDesc(
        UUID accountId, UUID userId, Pageable pageable);

    boolean existsByDedupKeyAndValueDate(String dedupKey, LocalDate valueDate);
}
