package com.rohit.nyvra.accounts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, UUID> {

    Optional<FinancialAccount> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    List<FinancialAccount> findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID userId);
}
