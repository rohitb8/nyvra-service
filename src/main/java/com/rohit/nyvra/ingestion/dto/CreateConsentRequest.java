package com.rohit.nyvra.ingestion.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to start an Account Aggregator consent.
 *
 * @param aaHandle      the user's aggregator handle, for example {@code someone@onemoney}
 * @param fipList       providers to cover; omit for all
 * @param dataRangeFrom first day of data to share
 * @param dataRangeTo   last day of data to share; must not precede {@code dataRangeFrom}
 * @param frequency     permitted fetch frequency such as {@code 1/DAY}; optional
 */
public record CreateConsentRequest(
    @NotBlank @Size(max = 120) String aaHandle,
    @Size(max = 50) List<@NotBlank @Size(max = 80) String> fipList,
    @NotNull LocalDate dataRangeFrom,
    @NotNull LocalDate dataRangeTo,
    @Size(max = 30) String frequency) {
}
