package com.rohit.nyvra.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in aggregator used until a real provider is integrated: accepts every consent, approves any
 * artefact id and returns no data. Active unless {@code nyvra.ingestion.aa.provider} names another adapter.
 */
@Component
@ConditionalOnProperty(name = "nyvra.ingestion.aa.provider", havingValue = "stub", matchIfMissing = true)
public class StubAggregatorGateway implements AggregatorGateway {

    /** How long a stub consent stays valid after approval. */
    private static final Duration CONSENT_VALIDITY = Duration.ofDays(365);

    /** {@inheritDoc} */
    @Override
    public String requestConsent(String aaHandle, List<String> fipList, LocalDate from, LocalDate to,
                                 String frequency) {
        return "stub-" + UUID.randomUUID();
    }

    /** {@inheritDoc} */
    @Override
    public Instant confirmConsent(String consentHandle, String consentId) {
        return Instant.now().plus(CONSENT_VALIDITY);
    }

    /** {@inheritDoc} */
    @Override
    public void revokeConsent(String consentId) {
        // nothing to tell a stub
    }

    /** {@inheritDoc} */
    @Override
    public List<FiPayload> fetch(String consentId, LocalDate from, LocalDate to) {
        return List.of();
    }
}
