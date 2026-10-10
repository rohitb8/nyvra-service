package com.rohit.nyvra.income;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link PayslipDocument}. Payslips are owned through their income entry, so
 * check entry ownership before calling.
 */
public interface PayslipDocumentRepository extends JpaRepository<PayslipDocument, UUID> {

    /**
     * Finds the payslips attached to one entry.
     *
     * @param incomeEntryId the entry
     * @return its payslips, possibly empty
     */
    List<PayslipDocument> findByIncomeEntryId(UUID incomeEntryId);

    /**
     * Finds the payslips attached to any of several entries, so a list can flag entries that have one.
     *
     * @param incomeEntryIds the entries
     * @return their payslips, possibly empty
     */
    List<PayslipDocument> findByIncomeEntryIdIn(Collection<UUID> incomeEntryIds);
}
