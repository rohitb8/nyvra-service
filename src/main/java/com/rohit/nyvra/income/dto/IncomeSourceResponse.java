package com.rohit.nyvra.income.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.IncomeCadence;
import com.rohit.nyvra.income.IncomeSource;
import com.rohit.nyvra.income.IncomeType;

public record IncomeSourceResponse(
    UUID id,
    String name,
    IncomeType type,
    IncomeCadence cadence,
    @JsonInclude(JsonInclude.Include.NON_NULL) MoneyDto expectedAmount,
    String currency,
    boolean active,
    Instant createdAt,
    Instant updatedAt) {

    public static IncomeSourceResponse from(IncomeSource s) {
        return new IncomeSourceResponse(s.getId(), s.getName(), s.getType(), s.getCadence(),
            MoneyDto.from(s.getExpectedAmount()), s.getCurrency(), s.isActive(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
