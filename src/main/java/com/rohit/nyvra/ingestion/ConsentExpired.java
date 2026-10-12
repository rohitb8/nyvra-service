package com.rohit.nyvra.ingestion;

import java.util.UUID;

/**
 * Published when a consent passes its expiry and is marked {@link ConsentStatus#EXPIRED}, so other modules
 * (for example notifications) can ask the user to renew it.
 *
 * @param userId    owner of the consent
 * @param consentId the expired consent
 */
public record ConsentExpired(UUID userId, UUID consentId) {
}
