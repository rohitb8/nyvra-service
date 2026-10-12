package com.rohit.nyvra.goals.dto;

import java.time.LocalDate;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.goals.GoalStatus;
import com.rohit.nyvra.goals.GoalType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for a partial update of a goal; an omitted (or {@code null}) field is unchanged. The start date and
 * currency are fixed at creation.
 *
 * @param name         new label, not blank, at most 80 characters
 * @param type         new type
 * @param targetAmount new target in INR, greater than zero
 * @param targetDate   new target date; must be after the start date
 * @param weight       new weight, 1 to 10
 * @param status       {@code ACTIVE} or {@code ARCHIVED}
 */
public record UpdateGoalRequest(
    @Pattern(regexp = ".*\\S.*", message = "must not be blank") @Size(max = 80) String name,
    GoalType type,
    @Valid MoneyDto targetAmount,
    LocalDate targetDate,
    @Min(1) @Max(10) Integer weight,
    GoalStatus status) {
}
