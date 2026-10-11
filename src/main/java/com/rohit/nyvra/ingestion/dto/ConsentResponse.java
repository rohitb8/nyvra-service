package com.rohit.nyvra.ingestion.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.ingestion.AggregatorConsent;
import com.rohit.nyvra.ingestion.ConsentStatus;

/**
 * An aggregator consent as the API shows it.
 *
 * @param id            consent id
 * @param aaHandle      the user's aggregator handle
 * @param fipList       providers covered, possibly empty
 * @param status        lifecycle state
 * @param dataRangeFrom first day of data covered
 * @param dataRangeTo   last day of data covered
 * @param frequency     permitted fetch frequency
 * @param expiresAt     when the approved consent lapses; absent until activated
 * @param createdAt     when the consent was requested
 */
public record ConsentResponse(UUID id, String aaHandle, List<String> fipList, ConsentStatus status,
                              LocalDate dataRangeFrom, LocalDate dataRangeTo, String frequency,
                              Instant expiresAt, Instant createdAt) {

    /**
     * Maps an entity to its response.
     *
     * @param consent the consent
     * @return the response
     */
    public static ConsentResponse from(AggregatorConsent consent) {
        return new ConsentResponse(consent.getId(), consent.getAaHandle(),
            consent.getFipList() == null ? List.of() : consent.getFipList(), consent.getStatus(),
            consent.getDataRangeFrom(), consent.getDataRangeTo(), consent.getFrequency(),
            consent.getExpiresAt(), consent.getCreatedAt());
    }
}
