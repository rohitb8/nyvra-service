package com.rohit.nyvra.ingestion.dto;

import java.time.LocalDate;

/**
 * Optional data range for a fetch. Either bound defaults to the consent's own range.
 *
 * @param from first day to fetch, or {@code null} for the consent's start
 * @param to   last day to fetch, or {@code null} for the consent's end
 */
public record TriggerFetchRequest(LocalDate from, LocalDate to) {
}
