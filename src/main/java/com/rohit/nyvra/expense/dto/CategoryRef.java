package com.rohit.nyvra.expense.dto;

import java.util.UUID;

import com.rohit.nyvra.expense.Category;

public record CategoryRef(UUID id, String name) {

    public static CategoryRef from(Category category) {
        return category == null ? null : new CategoryRef(category.getId(), category.getName());
    }
}
