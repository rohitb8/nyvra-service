package com.rohit.nyvra.accounts;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Card details are owned through their account: check account ownership before calling. */
public interface CardDetailRepository extends JpaRepository<CardDetail, UUID> {

    List<CardDetail> findByFinancialAccountId(UUID financialAccountId);

    List<CardDetail> findByFinancialAccountIdIn(Collection<UUID> financialAccountIds);
}
