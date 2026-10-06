package com.rohit.nyvra.accounts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FinancialAccountRepository
        extends JpaRepository<FinancialAccount, UUID>, JpaSpecificationExecutor<FinancialAccount> {

    Optional<FinancialAccount> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    List<FinancialAccount> findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID userId);
}
