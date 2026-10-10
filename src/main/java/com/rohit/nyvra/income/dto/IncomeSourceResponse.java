package com.rohit.nyvra.income.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.IncomeCadence;
import com.rohit.nyvra.income.IncomeSource;
import com.rohit.nyvra.income.IncomeType;

/**
 * API view of an income source.
 *
 * @param id             source id
 * @param name           label
 * @param type           kind of income
 * @param cadence        expected frequency
 * @param expectedAmount expected amount; omitted from the JSON when none is set
 * @param currency       ISO 4217 code
 * @param active         whether the source is in use
 * @param createdAt      creation time (UTC)
 * @param updatedAt      last modification time (UTC)
 */
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

    /**
     * Maps a source to its response.
     *
     * @param s the source
     * @return the response
     */
    public static IncomeSourceResponse from(IncomeSource s) {
        return new IncomeSourceResponse(s.getId(), s.getName(), s.getType(), s.getCadence(),
            MoneyDto.from(s.getExpectedAmount()), s.getCurrency(), s.isActive(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
