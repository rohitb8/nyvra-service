package com.rohit.nyvra.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One bank transaction in normalised form, free of any aggregator payload shape.
 *
 * @param dedupKey     stable keyed hash of account reference, value date, amount and narration; consumers
 *                     upsert on it, so re-ingesting never double-counts
 * @param valueDate    accounting date of the transaction
 * @param amount       positive amount, scale 2, in INR
 * @param direction    whether money left or entered the account
 * @param narration    the bank's free-text description; the input narration rules match against
 * @param balanceAfter account balance after the transaction, scale 2, or {@code null} if not reported
 */
public record IngestedTransaction(String dedupKey, LocalDate valueDate, BigDecimal amount,
                                  IngestedDirection direction, String narration, BigDecimal balanceAfter) {
}
