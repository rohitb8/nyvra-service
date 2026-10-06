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

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "necessity_default")
    private Necessity necessityDefault;

    @Column(name = "system", nullable = false, updatable = false)
    private boolean system;

    protected Category() {
        // for JPA
    }

    private Category(UUID userId, UUID parentId, String name, Necessity necessityDefault) {
        this.userId = userId;
        this.parentId = parentId;
        this.name = name;
        this.necessityDefault = necessityDefault;
        this.system = false;
    }

    public static Category custom(UUID userId, UUID parentId, String name, Necessity necessityDefault) {
        return new Category(
            Objects.requireNonNull(userId, "userId"),
            Objects.requireNonNull(parentId, "parentId"),
            Objects.requireNonNull(name, "name"),
            necessityDefault);
    }

    public void rename(String name) {
        requireCustom();
        this.name = Objects.requireNonNull(name, "name");
    }

    public void changeNecessityDefault(Necessity necessityDefault) {
        requireCustom();
        this.necessityDefault = necessityDefault;
    }

    private void requireCustom() {
        if (system) {
            throw new IllegalStateException("System categories are immutable");
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public UUID getParentId() {
        return parentId;
    }

    public Necessity getNecessityDefault() {
        return necessityDefault;
    }

    public boolean isSystem() {
        return system;
    }
}
