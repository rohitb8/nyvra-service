package com.rohit.nyvra.expense.dto;

import java.util.UUID;

import com.rohit.nyvra.expense.Necessity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Adds a custom child category.
 *
 * @param name             the display name, 1 to 60 characters
 * @param parentId         the top-level category to add it under
 * @param necessityDefault the necessity expenses default to; optional, the parent's default applies when absent
 */
public record CreateCategoryRequest(
    @NotBlank @Size(max = 60) String name,
    @NotNull UUID parentId,
    Necessity necessityDefault) {
}
