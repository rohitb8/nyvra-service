package com.rohit.nyvra.common.api;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Page envelope for bounded lists: {@code {content, page, size, totalElements, totalPages}}.
 *
 * @param <T> element type of the page
 * @param content the items on this page
 * @param page zero-based page index
 * @param size requested page size
 * @param totalElements total number of items across all pages
 * @param totalPages total number of pages
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    /**
     * Builds the envelope from a Spring Data page, converting each element.
     *
     * @param page the source page
     * @param mapper element conversion, for example entity to DTO
     * @param <E> source element type
     * @param <T> converted element type
     * @return the envelope with the same paging metadata
     */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
            page.getContent().stream().map(mapper).toList(),
            page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
