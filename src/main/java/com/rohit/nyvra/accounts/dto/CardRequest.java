package com.rohit.nyvra.accounts.dto;

import com.rohit.nyvra.accounts.CardNetwork;
import com.rohit.nyvra.common.money.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Card display metadata on account creation — never a PAN, CVV or expiry. */
public record CardRequest(
    @NotNull @Pattern(regexp = "^[0-9]{4}$", message = "must be exactly 4 digits") String last4,
    @NotNull CardNetwork network,
    @Size(max = 60) String label,
    @Valid MoneyDto creditLimit) {
}
