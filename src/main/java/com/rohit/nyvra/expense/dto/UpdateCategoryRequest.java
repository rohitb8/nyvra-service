package com.rohit.nyvra.expense.dto;

import com.rohit.nyvra.expense.Necessity;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a custom category; omitted fields are unchanged.
 *
 * @param name             the new display name, 1 to 60 characters
 * @param necessityDefault the new default necessity
 */
public record UpdateCategoryRequest(
    @Size(min = 1, max = 60) String name,
    Necessity necessityDefault) {
}
