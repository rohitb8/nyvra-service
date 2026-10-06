package com.rohit.nyvra.income;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Payslips are owned through their income entry: check entry ownership before calling. */
public interface PayslipDocumentRepository extends JpaRepository<PayslipDocument, UUID> {

    List<PayslipDocument> findByIncomeEntryId(UUID incomeEntryId);
}
