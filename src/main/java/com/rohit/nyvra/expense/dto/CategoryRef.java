package com.rohit.nyvra.expense.dto;

import java.util.UUID;

import com.rohit.nyvra.expense.Category;

/**
 * A lightweight reference to a category embedded in other responses.
 *
 * @param id   the category id
 * @param name the category's display name
 */
public record CategoryRef(UUID id, String name) {

    /**
     * Builds a reference from an entity.
     *
     * @param category the category, may be null
     * @return the reference, or null if {@code category} is null
     */
    public static CategoryRef from(Category category) {
        return category == null ? null : new CategoryRef(category.getId(), category.getName());
    }
}
