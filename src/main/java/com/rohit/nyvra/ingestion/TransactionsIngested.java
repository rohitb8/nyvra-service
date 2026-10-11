package com.rohit.nyvra.ingestion;

import java.util.List;
import java.util.UUID;

/**
 * Published (as a Spring application event) for each account whose transactions a fetch normalised.
 *
 * <p>This is the extension point for work that must happen to newly ingested transactions, such as
 * applying narration and MCC categorisation rules: a listener in the owning module consumes it, so
 * ingestion never depends on those modules. Consumers must be idempotent on
 * {@link IngestedTransaction#dedupKey()} because a normalisation run can be repeated.
 *
 * @param userId         owner of the data
 * @param fetchSessionId the fetch the transactions came from
 * @param fipId          provider the account belongs to
 * @param accountRef     provider-side account reference
 * @param transactions   the normalised transactions, never empty
 */
public record TransactionsIngested(UUID userId, UUID fetchSessionId, String fipId, String accountRef,
                                   List<IngestedTransaction> transactions) {
}
