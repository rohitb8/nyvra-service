package com.rohit.nyvra.ingestion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Confirms the user approved a requested consent at the aggregator.
 *
 * @param consentId the consent artefact id the aggregator issued on approval
 */
public record ActivateConsentRequest(@NotBlank @Size(max = 200) String consentId) {
}
