package com.rohit.nyvra.portfolio;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Composite primary key of {@link PriceQuote}: one price per instrument per moment. Used as a JPA
 * {@code @IdClass}, so the field names must match the entity's id fields.
 *
 * @param instrumentId the priced instrument
 * @param asOf         the moment the price applies to
 */
public record PriceQuoteId(UUID instrumentId, Instant asOf) implements Serializable {
}
