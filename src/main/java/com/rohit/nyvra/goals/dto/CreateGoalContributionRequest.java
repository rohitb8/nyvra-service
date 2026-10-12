package com.rohit.nyvra.goals.dto;

import java.time.LocalDate;

import com.rohit.nyvra.common.money.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for recording an amount set aside for a goal.
 *
 * @param amount        amount in INR, greater than zero
 * @param contributedOn accounting date, not in the future; defaults to today (India time) when omitted
 * @param note          optional remark, at most 200 characters
 */
public record CreateGoalContributionRequest(
    @NotNull @Valid MoneyDto amount,
    LocalDate contributedOn,
    @Size(max = 200) String note) {
}
