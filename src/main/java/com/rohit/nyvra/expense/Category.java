package com.rohit.nyvra.expense;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.rohit.nyvra.common.persistence.AbstractEntity;

/**
 * A spending category. System categories ({@code userId == null}) are seeded by
 * {@code V4.1__seed_categories.sql} and never created or edited by the app; users only add custom
 * children under an existing category — enforced by {@code chk_category_system_owner} and
 * {@code chk_category_custom_is_child}.
 */
@Entity
@Table(name = "category")
public class Category extends AbstractEntity {

    /** Owner of a custom category; null for a system category. Immutable. */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    /** Display name; unique among siblings (ignoring case), enforced by the service. */
    @Column(name = "name", nullable = false)
    private String name;

    /** Parent category id; null for a top-level system category. Immutable. */
    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    /** Necessity applied to expenses in this category unless overridden; may be null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "necessity_default")
    private Necessity necessityDefault;

    /** True for seeded system categories, which the app never edits. Immutable. */
    @Column(name = "system", nullable = false, updatable = false)
    private boolean system;

    /** Required by JPA; not for application use. */
    protected Category() {
        // for JPA
    }

    /**
     * Builds a non-system category.
     *
     * @param userId           the owner
     * @param parentId         the parent category
     * @param name             the display name
     * @param necessityDefault the default necessity, or null
     */
    private Category(UUID userId, UUID parentId, String name, Necessity necessityDefault) {
        this.userId = userId;
        this.parentId = parentId;
        this.name = name;
        this.necessityDefault = necessityDefault;
        this.system = false;
    }

    /**
     * Creates a user-owned custom category under an existing parent.
     *
     * @param userId           the owner, not null
     * @param parentId         the parent category, not null
     * @param name             the display name, not null
     * @param necessityDefault the default necessity, or null for none
     * @return the new, unsaved category
     * @throws NullPointerException if {@code userId}, {@code parentId} or {@code name} is null
     */
    public static Category custom(UUID userId, UUID parentId, String name, Necessity necessityDefault) {
        return new Category(
            Objects.requireNonNull(userId, "userId"),
            Objects.requireNonNull(parentId, "parentId"),
            Objects.requireNonNull(name, "name"),
            necessityDefault);
    }

    /**
     * Renames this category.
     *
     * @param name the new name, not null
     * @throws IllegalStateException if this is a system category
     * @throws NullPointerException  if {@code name} is null
     */
    public void rename(String name) {
        requireCustom();
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * Changes the default necessity.
     *
     * @param necessityDefault the new default, or null for none
     * @throws IllegalStateException if this is a system category
     */
    public void changeNecessityDefault(Necessity necessityDefault) {
        requireCustom();
        this.necessityDefault = necessityDefault;
    }

    /** Guards mutators: system categories are immutable. */
    private void requireCustom() {
        if (system) {
            throw new IllegalStateException("System categories are immutable");
        }
    }

    /** @return the owner, or null for a system category */
    public UUID getUserId() {
        return userId;
    }

    /** @return the display name */
    public String getName() {
        return name;
    }

    /** @return the parent category id, or null for a top-level category */
    public UUID getParentId() {
        return parentId;
    }

    /** @return the default necessity, or null */
    public Necessity getNecessityDefault() {
        return necessityDefault;
    }

    /** @return true for a seeded system category */
    public boolean isSystem() {
        return system;
    }
}
