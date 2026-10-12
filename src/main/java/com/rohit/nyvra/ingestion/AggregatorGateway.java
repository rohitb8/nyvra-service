package com.rohit.nyvra.ingestion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Outbound port to an RBI Account Aggregator. The only place AA provider specifics may live; everything
 * else in the application sees normalised events. The default {@link StubAggregatorGateway} is replaced by a
 * real adapter (selected through {@code nyvra.ingestion.aa.provider}) once a provider is chosen.
 */
public interface AggregatorGateway {

    /**
     * Asks the aggregator to start a consent request for the user.
     *
     * @param aaHandle the user's aggregator handle
     * @param fipList  providers to cover, may be {@code null}
     * @param from     first day of data wanted
     * @param to       last day of data wanted
     * @param frequency permitted fetch frequency, may be {@code null}
     * @return the aggregator's handle for the request
     * @throws AggregatorException if the aggregator rejects or cannot take the request
     */
    String requestConsent(String aaHandle, List<String> fipList, LocalDate from, LocalDate to, String frequency);

    /**
     * Confirms the user approved a requested consent.
     *
     * @param consentHandle the handle returned by {@link #requestConsent}
     * @param consentId     the consent artefact id the aggregator issued on approval
     * @return when the approved consent lapses
     * @throws AggregatorException if the consent was not approved or the id does not match
     */
    Instant confirmConsent(String consentHandle, String consentId);

    /**
     * Tells the aggregator the consent is withdrawn.
     *
     * @param consentId the approved consent artefact id, or {@code null} if it was never approved
     * @throws AggregatorException if the aggregator cannot record the revocation
     */
    void revokeConsent(String consentId);

    /**
     * Fetches financial information under an approved consent.
     *
     * @param consentId the approved consent artefact id
     * @param from      first day of data wanted
     * @param to        last day of data wanted
     * @return the provider payloads, empty when there is no data
     * @throws AggregatorException if the fetch fails
     */
    List<FiPayload> fetch(String consentId, LocalDate from, LocalDate to);

    /**
     * One provider payload as delivered.
     *
     * @param fipId       provider id
     * @param accountRef  provider-side account reference
     * @param payloadType FI type
     * @param json        the payload JSON
     */
    record FiPayload(String fipId, String accountRef, PayloadType payloadType, String json) {
    }
}
