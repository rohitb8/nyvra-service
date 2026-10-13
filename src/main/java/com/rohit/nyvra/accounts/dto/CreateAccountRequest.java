package com.rohit.nyvra.accounts.dto;

import java.time.Instant;

import com.rohit.nyvra.accounts.AccountType;
import com.rohit.nyvra.common.money.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to create a manual account.
 *
 * @param type account type
 * @param label display name, required, at most 60 characters
 * @param institution optional bank or provider name, at most 80 characters
 * @param maskedNumber optional last four digits, optionally prefixed by mask characters
 * @param currentBalance opening balance (INR only in v1)
 * @param balanceAsOf optional instant the balance is valid as of; defaults to now
 * @param card card details; required for CREDIT_CARD and forbidden otherwise
 */
public record CreateAccountRequest(
    @NotNull AccountType type,
    @NotBlank @Size(max = 60) String label,
    @Size(max = 80) String institution,
    @Pattern(regexp = "^[Xx*]*[0-9]{4}$", message = "must be the last 4 digits, optionally prefixed by mask characters")
    String maskedNumber,
    @NotNull @Valid MoneyDto currentBalance,
    Instant balanceAsOf,
    @Valid CardRequest card) {
}
