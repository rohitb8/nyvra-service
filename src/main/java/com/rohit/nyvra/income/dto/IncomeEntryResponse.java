package com.rohit.nyvra.income.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.IncomeEntry;
import com.rohit.nyvra.income.IncomeOrigin;
import com.rohit.nyvra.income.IncomeSource;
import com.rohit.nyvra.income.IncomeType;

/**
 * API view of an income entry, enriched with its source's name and type so lists need no second call.
 *
 * @param id                  entry id
 * @param sourceId            owning source id
 * @param sourceName          name of the source
 * @param sourceType          type of the source
 * @param periodStart         first covered day, inclusive
 * @param periodEnd           last covered day, inclusive
 * @param grossAmount         amount before deductions
 * @param netAmount           amount received
 * @param receivedOn          date the money arrived
 * @param linkedTransactionId linked Accounts transaction; omitted from the JSON when absent
 * @param origin              how the entry was created
 * @param hasPayslip          whether a payslip document is attached
 */
public record IncomeEntryResponse(
    UUID id,
    UUID sourceId,
    String sourceName,
    IncomeType sourceType,
    LocalDate periodStart,
    LocalDate periodEnd,
    MoneyDto grossAmount,
    MoneyDto netAmount,
    LocalDate receivedOn,
    @JsonInclude(JsonInclude.Include.NON_NULL) UUID linkedTransactionId,
    IncomeOrigin origin,
    boolean hasPayslip) {

    /**
     * Maps an entry to its response.
     *
     * @param e the entry
     * @param source the entry's source, for name and type
     * @param hasPayslip whether a payslip is attached
     * @return the response
     */
    public static IncomeEntryResponse from(IncomeEntry e, IncomeSource source, boolean hasPayslip) {
        return new IncomeEntryResponse(e.getId(), e.getSourceId(), source.getName(), source.getType(),
            e.getPeriodStart(), e.getPeriodEnd(), MoneyDto.from(e.getGrossAmount()), MoneyDto.from(e.getNetAmount()),
            e.getReceivedOn(), e.getLinkedTransactionId(), e.getOrigin(), hasPayslip);
    }
}
