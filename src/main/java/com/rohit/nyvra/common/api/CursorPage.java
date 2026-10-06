package com.rohit.nyvra.common.api;

import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Envelope for keyset-paginated feeds: {@code {content, nextCursor, limit}}; no {@code nextCursor} on the last page. */
public record CursorPage<T>(List<T> content, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor, int limit) {

    public <R> CursorPage<R> map(Function<T, R> mapper) {
        return new CursorPage<>(content.stream().map(mapper).toList(), nextCursor, limit);
    }
}
