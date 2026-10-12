package com.rohit.nyvra.networth.dto;

import java.time.LocalDate;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.networth.ManualItemClass;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a hand-maintained entry; omitted fields are unchanged. Kind and currency cannot change.
 * Sending a new {@code value} without {@code valueAsOf} confirms it as of today.
 *
 * @param itemClass              the new category
 * @param label                  the new name
 * @param value                  the new value; INR, not negative
 * @param valueAsOf              the day the value was confirmed
 * @param revaluationCadenceDays the new freshness window in days
 */
public record UpdateManualItemRequest(
    ManualItemClass itemClass,
    @Size(max = 80) String label,
    @Valid MoneyDto value,
    LocalDate valueAsOf,
    @Min(1) @Max(3650) Integer revaluationCadenceDays) {
}
