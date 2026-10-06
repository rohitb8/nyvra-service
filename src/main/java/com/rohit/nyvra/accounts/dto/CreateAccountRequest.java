package com.rohit.nyvra.accounts.dto;

import java.time.Instant;

import com.rohit.nyvra.accounts.AccountType;
import com.rohit.nyvra.common.money.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
