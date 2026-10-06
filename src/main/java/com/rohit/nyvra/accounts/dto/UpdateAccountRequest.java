package com.rohit.nyvra.accounts.dto;

import java.time.Instant;

import com.rohit.nyvra.common.money.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Omitted fields are unchanged. */
public record UpdateAccountRequest(
    @Pattern(regexp = ".*\\S.*", message = "must not be blank") @Size(max = 60) String label,
    @Size(max = 80) String institution,
    @Valid MoneyDto currentBalance,
    Instant balanceAsOf,
    @Valid CardUpdate card) {

    public record CardUpdate(@Size(max = 60) String label, @Valid MoneyDto creditLimit) {
    }
}
