package com.rohit.nyvra.expense.dto;

import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.expense.Category;
import com.rohit.nyvra.expense.Necessity;

/**
 * A category on the wire. {@code children} is present only in the category tree, on every node of it.
 *
 * @param id               the category id
 * @param name             the display name
 * @param parentId         the parent category; absent for a top-level category
 * @param necessityDefault the necessity expenses in this category default to; may be absent
 * @param system           true for a seeded, immutable category
 * @param children         nested categories, or null outside the tree
 */
public record CategoryResponse(
    UUID id,
    String name,
    UUID parentId,
    Necessity necessityDefault,
    boolean system,
    List<CategoryResponse> children) {

    /**
     * Maps a category without children.
     *
     * @param category the category
     * @return its response, with no {@code children}
     */
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getParentId(),
            category.getNecessityDefault(), category.isSystem(), null);
    }

    /**
     * Maps a category together with its nested children.
     *
     * @param category the category
     * @param children its already-mapped children, possibly empty
     * @return its response
     */
    public static CategoryResponse withChildren(Category category, List<CategoryResponse> children) {
        return new CategoryResponse(category.getId(), category.getName(), category.getParentId(),
            category.getNecessityDefault(), category.isSystem(), children);
    }
}
