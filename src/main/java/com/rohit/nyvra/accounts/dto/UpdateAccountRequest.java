package com.rohit.nyvra.accounts.dto;

import java.time.Instant;

import com.rohit.nyvra.common.money.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial account update; omitted (null) fields are unchanged.
 *
 * @param label new display name, non-blank, at most 60 characters
 * @param institution new institution, at most 80 characters
 * @param currentBalance new balance (manual accounts only)
 * @param balanceAsOf new as-of instant (manual accounts only)
 * @param card card changes (credit-card accounts only)
 */
public record UpdateAccountRequest(
    @Pattern(regexp = ".*\\S.*", message = "must not be blank") @Size(max = 60) String label,
    @Size(max = 80) String institution,
    @Valid MoneyDto currentBalance,
    Instant balanceAsOf,
    @Valid CardUpdate card) {

    /**
     * Partial card update.
     *
     * @param label new card label, at most 60 characters
     * @param creditLimit new credit limit (manual accounts only)
     */
    public record CardUpdate(@Size(max = 60) String label, @Valid MoneyDto creditLimit) {
    }
}
