package com.rohit.nyvra.income;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IncomeSourceRepository
    extends JpaRepository<IncomeSource, UUID>, JpaSpecificationExecutor<IncomeSource> {

    Optional<IncomeSource> findByIdAndUserId(UUID id, UUID userId);

    List<IncomeSource> findByUserIdAndActiveTrueOrderByNameAsc(UUID userId);

    List<IncomeSource> findByIdIn(Collection<UUID> ids);
}
