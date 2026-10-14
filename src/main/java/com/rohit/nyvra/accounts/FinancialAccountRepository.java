package com.rohit.nyvra.accounts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Persistence for {@link FinancialAccount}; lookups exclude soft-deleted rows and are user-scoped. */
public interface FinancialAccountRepository
        extends JpaRepository<FinancialAccount, UUID>, JpaSpecificationExecutor<FinancialAccount> {

    /**
     * Finds a live account owned by the user.
     *
     * @param id account id
     * @param userId owner
     * @return the account, or empty
     */
    Optional<FinancialAccount> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    /**
     * A user's live accounts, oldest first.
     *
     * @param userId owner
     * @return accounts
     */
    List<FinancialAccount> findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID userId);
}
