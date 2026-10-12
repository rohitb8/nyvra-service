package com.rohit.nyvra.networth.dto;

import java.time.LocalDate;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.networth.ManualItemClass;
import com.rohit.nyvra.networth.ManualItemKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to add a hand-maintained asset or liability.
 *
 * @param kind                   asset or liability
 * @param itemClass              the category
 * @param label                  optional name
 * @param value                  the current value; INR, not negative
 * @param valueAsOf              the day the value was confirmed; defaults to today
 * @param revaluationCadenceDays days a value stays fresh; omit for never stale
 */
public record CreateManualItemRequest(
    @NotNull ManualItemKind kind,
    @NotNull ManualItemClass itemClass,
    @Size(max = 80) String label,
    @NotNull @Valid MoneyDto value,
    LocalDate valueAsOf,
    @Min(1) @Max(3650) Integer revaluationCadenceDays) {
}
