package com.rohit.nyvra.income;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IncomeSourceRepository extends JpaRepository<IncomeSource, UUID> {

    Optional<IncomeSource> findByIdAndUserId(UUID id, UUID userId);

    List<IncomeSource> findByUserIdAndActiveTrueOrderByNameAsc(UUID userId);
}
