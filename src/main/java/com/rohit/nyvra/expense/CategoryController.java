package com.rohit.nyvra.expense;

import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.expense.dto.CategoryResponse;
import com.rohit.nyvra.expense.dto.CreateCategoryRequest;
import com.rohit.nyvra.expense.dto.UpdateCategoryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Category endpoints under {@code /api/v1/categories} per {@code openapi/nyvra-api-v1.yaml}. Thin: every rule
 * lives in {@link CategoryService}.
 */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories")
@SecurityRequirement(name = "keycloak")
public class CategoryController {

    /** Use-case layer every endpoint delegates to. */
    private final CategoryService service;

    /**
     * Creates the controller.
     *
     * @param service the category use-case service
     */
    public CategoryController(CategoryService service) {
        this.service = service;
    }

    /**
     * The category tree: system categories plus the caller's custom children. Unpaged.
     *
     * @return the top-level categories, each with its children
     */
    @GetMapping
    @Operation(summary = "Category tree",
        description = "System categories plus the caller's custom children, as a tree. Unpaged.")
    public List<CategoryResponse> tree() {
        return service.tree();
    }

    /**
     * Adds a custom child category.
     *
     * @param request the new category
     * @return 201 with the category and its {@code Location}
     */
    @PostMapping
    @Operation(summary = "Add a custom child category")
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        CategoryResponse created = service.create(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    /**
     * Renames a custom category or changes its default necessity.
     *
     * @param categoryId the category id
     * @param request    the fields to change
     * @return the updated category; a system category is rejected with 422 {@code SYSTEM_CATEGORY_IMMUTABLE}
     */
    @PatchMapping("/{categoryId}")
    @Operation(summary = "Rename a custom category or change its default necessity",
        description = "System categories return `422 SYSTEM_CATEGORY_IMMUTABLE`.")
    public CategoryResponse update(@PathVariable UUID categoryId, @Valid @RequestBody UpdateCategoryRequest request) {
        return service.update(categoryId, request);
    }

    /**
     * Deletes a custom category.
     *
     * @param categoryId the category id; one still in use is rejected with 409 {@code CATEGORY_IN_USE}
     */
    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a custom category",
        description = "`409 CATEGORY_IN_USE` while expenses or rules still point at it; system categories return "
            + "`422 SYSTEM_CATEGORY_IMMUTABLE`.")
    public void delete(@PathVariable UUID categoryId) {
        service.delete(categoryId);
    }
}
