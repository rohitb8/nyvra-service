package com.rohit.nyvra.networth.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.networth.ManualAssetLiability;
import com.rohit.nyvra.networth.ManualItemClass;
import com.rohit.nyvra.networth.ManualItemKind;

/**
 * A hand-maintained asset or liability.
 *
 * @param id                      the entry id
 * @param kind                    asset or liability
 * @param itemClass               the category
 * @param label                   the name, absent when none was given
 * @param value                   the current value
 * @param valueAsOf               the day the value was last confirmed
 * @param revaluationCadenceDays  days a value stays fresh; absent means never stale
 * @param stale                   whether the value is older than its cadence allows
 * @param createdAt               when the entry was created
 * @param updatedAt               when the entry last changed
 */
public record ManualItemResponse(
    UUID id,
    ManualItemKind kind,
    ManualItemClass itemClass,
    String label,
    MoneyDto value,
    LocalDate valueAsOf,
    Integer revaluationCadenceDays,
    boolean stale,
    Instant createdAt,
    Instant updatedAt) {

    /**
     * Maps an entity to its wire form.
     *
     * @param item  the entity
     * @param today the day staleness is judged against
     * @return the response
     */
    public static ManualItemResponse from(ManualAssetLiability item, LocalDate today) {
        return new ManualItemResponse(item.getId(), item.getKind(), item.getItemClass(), item.getLabel(),
            MoneyDto.from(item.getValue()), item.getValueAsOf(),
            item.getRevaluationCadence() == null ? null : (int) item.getRevaluationCadence().toDays(),
            item.isStale(today), item.getCreatedAt(), item.getUpdatedAt());
    }
}
