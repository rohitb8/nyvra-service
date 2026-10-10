package com.rohit.nyvra.income.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.IncomeEntry;
import com.rohit.nyvra.income.IncomeOrigin;
import com.rohit.nyvra.income.IncomeSource;
import com.rohit.nyvra.income.IncomeType;

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

    public static IncomeEntryResponse from(IncomeEntry e, IncomeSource source, boolean hasPayslip) {
        return new IncomeEntryResponse(e.getId(), e.getSourceId(), source.getName(), source.getType(),
            e.getPeriodStart(), e.getPeriodEnd(), MoneyDto.from(e.getGrossAmount()), MoneyDto.from(e.getNetAmount()),
            e.getReceivedOn(), e.getLinkedTransactionId(), e.getOrigin(), hasPayslip);
    }
}
