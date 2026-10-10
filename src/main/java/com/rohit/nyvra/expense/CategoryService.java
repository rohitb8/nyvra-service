package com.rohit.nyvra.expense;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.exception.ConflictException;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.common.exception.UnprocessableEntityException;
import com.rohit.nyvra.expense.dto.CategoryResponse;
import com.rohit.nyvra.expense.dto.CreateCategoryRequest;
import com.rohit.nyvra.expense.dto.UpdateCategoryRequest;
import com.rohit.nyvra.user.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Category use cases for the signed-in user. The system tree is read-only; users add, rename and delete their own
 * custom children, each of which sits directly under a top-level category (a two-level tree, which is what expenses
 * assume). A system category and another user's category look different on purpose: the first is a 422, the second
 * indistinguishable from a missing one (404).
 */
@Service
public class CategoryService {

    /** Code for an attempt to change a seeded category. */
    static final String SYSTEM_CATEGORY_IMMUTABLE = "SYSTEM_CATEGORY_IMMUTABLE";

    /** Code for deleting a category that expenses or rules still use. */
    static final String CATEGORY_IN_USE = "CATEGORY_IN_USE";

    /** Code for a sibling that already has the name. */
    static final String CATEGORY_NAME_TAKEN = "CATEGORY_NAME_TAKEN";

    /** Code for a parent that is itself a child category. */
    static final String INVALID_PARENT_CATEGORY = "INVALID_PARENT_CATEGORY";

    /** Category persistence. */
    private final CategoryRepository categories;

    /** Used to check whether a category is still referenced. */
    private final ExpenseRepository expenses;

    /** Used to check whether a category is still referenced. */
    private final CategorisationRuleRepository rules;

    /** Resolves the caller. */
    private final CurrentUserService currentUser;

    /**
     * Creates the service.
     *
     * @param categories  category persistence
     * @param expenses    expense persistence, for the in-use check
     * @param rules       rule persistence, for the in-use check
     * @param currentUser resolves the caller
     */
    public CategoryService(CategoryRepository categories, ExpenseRepository expenses,
                           CategorisationRuleRepository rules, CurrentUserService currentUser) {
        this.categories = categories;
        this.expenses = expenses;
        this.rules = rules;
        this.currentUser = currentUser;
    }

    /**
     * The category tree the caller sees: every system category plus their own custom ones, nested under their
     * parents and sorted by name.
     *
     * @return the top-level categories, each with its {@code children}
     */
    @Transactional(readOnly = true)
    public List<CategoryResponse> tree() {
        UUID userId = currentUser.currentUser().getId();
        List<Category> visible = categories.findVisibleTo(userId);
        Map<UUID, List<Category>> byParent = visible.stream()
            .filter(c -> c.getParentId() != null)
            .collect(Collectors.groupingBy(Category::getParentId));
        return visible.stream()
            .filter(c -> c.getParentId() == null)
            .sorted(Comparator.comparing(Category::getName))
            .map(c -> node(c, byParent))
            .toList();
    }

    /**
     * Adds a custom child category for the caller.
     *
     * @param request the new category
     * @return the created category
     * @throws ResourceNotFoundException if the parent is not visible to the caller
     * @throws UnprocessableEntityException {@code INVALID_PARENT_CATEGORY} if the parent is not top-level
     * @throws BadRequestException if the name is blank
     * @throws ConflictException {@code CATEGORY_NAME_TAKEN} if a sibling already has the name
     */
    @Transactional
    public CategoryResponse create(CreateCategoryRequest request) {
        UUID userId = currentUser.currentUser().getId();
        Map<UUID, Category> visible = visibleById(userId);
        Category parent = visible.get(request.parentId());
        if (parent == null) {
            throw ResourceNotFoundException.of("Category", request.parentId());
        }
        if (parent.getParentId() != null) {
            throw new UnprocessableEntityException(INVALID_PARENT_CATEGORY,
                "Categories can only be added under a top-level category");
        }
        String name = cleanName(request.name());
        requireNameFree(visible, parent.getId(), name, null);
        Category created = categories.save(Category.custom(userId, parent.getId(), name, request.necessityDefault()));
        return CategoryResponse.from(created);
    }

    /**
     * Renames a custom category and/or changes its default necessity.
     *
     * @param id      the category id
     * @param request the fields to change
     * @return the updated category
     * @throws ResourceNotFoundException if the category is not visible to the caller
     * @throws UnprocessableEntityException {@code SYSTEM_CATEGORY_IMMUTABLE} for a system category
     * @throws BadRequestException if the new name is blank
     * @throws ConflictException {@code CATEGORY_NAME_TAKEN} if a sibling already has the new name
     */
    @Transactional
    public CategoryResponse update(UUID id, UpdateCategoryRequest request) {
        UUID userId = currentUser.currentUser().getId();
        Map<UUID, Category> visible = visibleById(userId);
        Category category = ownCustom(visible, id);
        if (request.name() != null) {
            String name = cleanName(request.name());
            requireNameFree(visible, category.getParentId(), name, category.getId());
            category.rename(name);
        }
        if (request.necessityDefault() != null) {
            category.changeNecessityDefault(request.necessityDefault());
        }
        return CategoryResponse.from(categories.saveAndFlush(category));
    }

    /**
     * Deletes a custom category.
     *
     * @param id the category id
     * @throws ResourceNotFoundException if the category is not visible to the caller
     * @throws UnprocessableEntityException {@code SYSTEM_CATEGORY_IMMUTABLE} for a system category
     * @throws ConflictException {@code CATEGORY_IN_USE} if an expense or rule still points at it
     */
    @Transactional
    public void delete(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        Category category = ownCustom(visibleById(userId), id);
        if (expenses.existsByCategory(userId, id) || rules.existsByCategoryId(id)) {
            throw new ConflictException(CATEGORY_IN_USE,
                "Recategorise its expenses and remove its rules before deleting this category");
        }
        categories.delete(category);
    }

    private Map<UUID, Category> visibleById(UUID userId) {
        return categories.findVisibleTo(userId).stream().collect(Collectors.toMap(Category::getId, Function.identity()));
    }

    /** Looks the category up among those the caller can see and rejects system categories. */
    private static Category ownCustom(Map<UUID, Category> visible, UUID id) {
        Category category = visible.get(id);
        if (category == null) {
            throw ResourceNotFoundException.of("Category", id);
        }
        if (category.isSystem()) {
            throw new UnprocessableEntityException(SYSTEM_CATEGORY_IMMUTABLE, "System categories can't be changed");
        }
        return category;
    }

    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw new BadRequestException("name must not be blank");
        }
        return name;
    }

    /** Rejects a name already used (ignoring case) by another category under the same parent. */
    private static void requireNameFree(Map<UUID, Category> visible, UUID parentId, String name, UUID ignoreId) {
        boolean taken = visible.values().stream()
            .filter(c -> parentId.equals(c.getParentId()) && !c.getId().equals(ignoreId))
            .anyMatch(c -> c.getName().equalsIgnoreCase(name));
        if (taken) {
            throw new ConflictException(CATEGORY_NAME_TAKEN, "There is already a category called '" + name + "' here");
        }
    }

    private static CategoryResponse node(Category category, Map<UUID, List<Category>> byParent) {
        List<CategoryResponse> children = byParent.getOrDefault(category.getId(), List.of()).stream()
            .sorted(Comparator.comparing(Category::getName))
            .map(c -> node(c, byParent))
            .toList();
        return CategoryResponse.withChildren(category, children);
    }
}
