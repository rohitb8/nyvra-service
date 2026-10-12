package com.rohit.nyvra.ingestion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published for a provider payload that reports one account-level figure rather than transactions: a term or
 * recurring deposit, an EPF or NPS account, a loan or a credit card. Consumers decide what the figure means
 * for their module (an asset value, an outstanding liability) from {@code payloadType}; the amount itself is
 * always non-negative.
 *
 * <p>Consumers must be idempotent: the same snapshot can be published again when a fetch is normalised again.
 *
 * @param userId         owner of the data
 * @param fetchSessionId the fetch the payload came from
 * @param fipId          provider the account belongs to
 * @param accountRef     provider-side account reference
 * @param payloadType    which kind of account this is; never {@link PayloadType#DEPOSIT},
 *                       {@link PayloadType#MUTUAL_FUND} or {@link PayloadType#EQUITIES}
 * @param amount         current value for assets, outstanding amount for a loan, amount due for a card; scale 2, INR
 * @param asOf           when the provider says the figure was true, or {@code null} if it did not say
 */
public record AccountSnapshotIngested(UUID userId, UUID fetchSessionId, String fipId, String accountRef,
                                      PayloadType payloadType, BigDecimal amount, Instant asOf) {
}
