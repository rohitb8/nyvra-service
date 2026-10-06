package com.rohit.nyvra.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.AbstractEntity;

/**
 * A categorised spend. Monthly-partitioned by {@code date} (real PK {@code (id, date)}; {@code id} alone is
 * unique and mapped as the JPA id — see {@code AccountTransaction}).
 *
 * <p>Splits: {@link #splitOf} creates a child with {@code origin = SPLIT} on the parent's date (the DB
 * foreign key is {@code (parent_expense_id, date)}). Children summing to the parent is a service-layer
 * invariant. {@code transactionId} references an Accounts transaction by id only — no foreign key.
 */
@Entity
@Table(name = "expense")
public class Expense extends AbstractEntity {

    @Column(name = "date", nullable = false, updatable = false)
    private LocalDate date;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "parent_expense_id", updatable = false)
    private UUID parentExpenseId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "subcategory_id")
    private UUID subcategoryId;

    @Column(name = "merchant")
    private String merchant;

    @Enumerated(EnumType.STRING)
    @Column(name = "necessity", nullable = false)
    private Necessity necessity;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false)
    private ExpenseOrigin origin;

    @Column(name = "excluded_from_habits", nullable = false)
    private boolean excludedFromHabits;

    protected Expense() {
        // for JPA
    }

    /** @param transactionId null when manually entered */
    public Expense(UUID userId, LocalDate date, Money amount, UUID categoryId, UUID subcategoryId,
                   String merchant, Necessity necessity, ExpenseOrigin origin, UUID transactionId) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.date = Objects.requireNonNull(date, "date");
        this.amount = amount.amount();
        this.currency = amount.currency();
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
        this.subcategoryId = subcategoryId;
        this.merchant = merchant;
        this.necessity = Objects.requireNonNull(necessity, "necessity");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.transactionId = transactionId;
    }

    public static Expense splitOf(Expense parent, Money amount, UUID categoryId, UUID subcategoryId,
                                  Necessity necessity) {
        parent.getAmount().requireSameCurrency(amount);
        Expense child = new Expense(parent.userId, parent.date, amount, categoryId, subcategoryId,
            parent.merchant, necessity, ExpenseOrigin.SPLIT, null);
        child.parentExpenseId = parent.getId();
        return child;
    }

    public void recategorise(UUID categoryId, UUID subcategoryId, Necessity necessity) {
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
        this.subcategoryId = subcategoryId;
        this.necessity = Objects.requireNonNull(necessity, "necessity");
    }

    public void setExcludedFromHabits(boolean excludedFromHabits) {
        this.excludedFromHabits = excludedFromHabits;
    }

    public LocalDate getDate() {
        return date;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getParentExpenseId() {
        return parentExpenseId;
    }

    public Money getAmount() {
        return Money.of(amount, currency);
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public UUID getSubcategoryId() {
        return subcategoryId;
    }

    public String getMerchant() {
        return merchant;
    }

    public Necessity getNecessity() {
        return necessity;
    }

    public ExpenseOrigin getOrigin() {
        return origin;
    }

    public boolean isExcludedFromHabits() {
        return excludedFromHabits;
    }
}
