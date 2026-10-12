package com.rohit.nyvra.goals.dto;

import java.time.LocalDate;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.goals.GoalType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a goal.
 *
 * @param name         label, not blank, at most 80 characters
 * @param type         what the goal is for
 * @param targetAmount amount to reach, in INR, greater than zero
 * @param startDate    first day of accrual; defaults to today (India time) when omitted
 * @param targetDate   day by which to reach the target; must be after the start date
 * @param weight       relative importance among goals, 1 to 10; defaults to 1 when omitted
 */
public record CreateGoalRequest(
    @NotBlank @Size(max = 80) String name,
    @NotNull GoalType type,
    @NotNull @Valid MoneyDto targetAmount,
    LocalDate startDate,
    @NotNull LocalDate targetDate,
    @Min(1) @Max(10) Integer weight) {
}
