package com.rohit.nyvra.ingestion;

import java.util.List;
import java.util.UUID;

/**
 * Published for each mutual-fund or equity payload, carrying the holdings it reported. The portfolio module is
 * the intended consumer; ingestion never calls it. Consumers must be idempotent, because the same holdings can
 * be published again when a fetch is normalised again.
 *
 * @param userId         owner of the data
 * @param fetchSessionId the fetch the payload came from
 * @param fipId          provider the folio or demat account belongs to
 * @param accountRef     provider-side account reference
 * @param payloadType    {@link PayloadType#MUTUAL_FUND} or {@link PayloadType#EQUITIES}
 * @param holdings       the holdings, never empty
 */
public record HoldingsIngested(UUID userId, UUID fetchSessionId, String fipId, String accountRef,
                               PayloadType payloadType, List<IngestedHolding> holdings) {
}
