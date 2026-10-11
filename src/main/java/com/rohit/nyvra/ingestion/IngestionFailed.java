package com.rohit.nyvra.ingestion;

import java.util.UUID;

/**
 * Published when a fetch fails, so other modules (for example notifications) can react without depending on
 * ingestion internals.
 *
 * @param userId         owner of the consent
 * @param consentId      the consent the fetch ran under
 * @param fetchSessionId the failed fetch
 * @param reason         short, personal-data-free failure reason
 */
public record IngestionFailed(UUID userId, UUID consentId, UUID fetchSessionId, String reason) {
}
