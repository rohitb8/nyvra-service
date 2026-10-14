package com.rohit.nyvra.common.api;

import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Envelope for keyset-paginated feeds: {@code {content, nextCursor, limit}}; no {@code nextCursor} on the last page.
 *
 * @param <T> element type of the page
 * @param content the items on this page, in feed order
 * @param nextCursor opaque cursor for the next page; {@code null} (omitted from JSON) on the last page
 * @param limit the page size that was applied
 */
public record CursorPage<T>(List<T> content, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor, int limit) {

    /**
     * Converts each item with {@code mapper}, keeping the cursor and limit unchanged.
     *
     * @param mapper element conversion applied to every item
     * @param <R> converted element type
     * @return a new page of the mapped items
     */
    public <R> CursorPage<R> map(Function<T, R> mapper) {
        return new CursorPage<>(content.stream().map(mapper).toList(), nextCursor, limit);
    }
}
