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
 *
 * <p>{@link #getCategorySource()} records whether the system or the user chose the category: a manual expense
 * and any {@link #recategorise} are {@link CategorySource#USER}, everything else starts {@link CategorySource#AUTO}.
 */
@Entity
@Table(name = "expense")
public class Expense extends AbstractEntity {

    /** Accounting date; also the partition key. */
    @Column(name = "date", nullable = false)
    private LocalDate date;

    /** Owner. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Source Accounts transaction, by id only; null when manually entered. */
    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    /** For a split part, the parent expense; null otherwise. */
    @Column(name = "parent_expense_id", updatable = false)
    private UUID parentExpenseId;

    /** Free-text note, carried by split parts. */
    @Column(name = "note")
    private String note;

    /** Amount, scale 2. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** ISO-4217 currency code of {@link #amount}. */
    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** The category the spend belongs to. */
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    /** Optional child of {@link #categoryId}. */
    @Column(name = "subcategory_id")
    private UUID subcategoryId;

    /** Merchant name, when known. */
    @Column(name = "merchant")
    private String merchant;

    /** How necessary the spend was. */
    @Enumerated(EnumType.STRING)
    @Column(name = "necessity", nullable = false)
    private Necessity necessity;

    /** Where the expense came from. */
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false)
    private ExpenseOrigin origin;

    /** Left out of spending totals when true. */
    @Column(name = "excluded_from_habits", nullable = false)
    private boolean excludedFromHabits;

    /** Whether the system or the user picked the category. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category_source", nullable = false)
    private CategorySource categorySource;

    /** For JPA. */
    protected Expense() {
        // for JPA
    }

    /**
     * Creates an expense. A {@link ExpenseOrigin#MANUAL} expense is {@link CategorySource#USER}; any other
     * origin starts {@link CategorySource#AUTO}.
     *
     * @param userId        the owner
     * @param date          the accounting date
     * @param amount        the amount
     * @param categoryId    the category
     * @param subcategoryId an optional child of {@code categoryId}
     * @param merchant      the merchant, or null
     * @param necessity     how necessary the spend was
     * @param origin        where the expense came from
     * @param transactionId null when manually entered
     */
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
        this.categorySource = origin == ExpenseOrigin.MANUAL ? CategorySource.USER : CategorySource.AUTO;
    }

    /**
     * Creates a split part of {@code parent} on the parent's date and merchant.
     *
     * @param parent        the expense being split
     * @param amount        the part's amount, in the parent's currency
     * @param categoryId    the part's category
     * @param subcategoryId an optional child of {@code categoryId}
     * @param necessity     the part's necessity
     * @param note          an optional note
     * @return the unsaved child
     */
    public static Expense splitOf(Expense parent, Money amount, UUID categoryId, UUID subcategoryId,
                                  Necessity necessity, String note) {
        parent.getAmount().requireSameCurrency(amount);
        Expense child = new Expense(parent.userId, parent.date, amount, categoryId, subcategoryId,
            parent.merchant, necessity, ExpenseOrigin.SPLIT, null);
        child.parentExpenseId = parent.getId();
        child.note = note;
        child.categorySource = CategorySource.USER;
        return child;
    }

    /**
     * Changes the category by hand; the expense becomes {@link CategorySource#USER} so rules leave it alone.
     *
     * @param categoryId    the new category
     * @param subcategoryId an optional child of {@code categoryId}
     * @param necessity     the new necessity
     */
    public void recategorise(UUID categoryId, UUID subcategoryId, Necessity necessity) {
        applyCategory(categoryId, subcategoryId, necessity);
        this.categorySource = CategorySource.USER;
    }

    /**
     * Changes the category because a categorisation rule matched; the expense stays
     * {@link CategorySource#AUTO}.
     *
     * @param categoryId    the new category
     * @param subcategoryId an optional child of {@code categoryId}
     * @param necessity     the new necessity
     */
    public void autoRecategorise(UUID categoryId, UUID subcategoryId, Necessity necessity) {
        applyCategory(categoryId, subcategoryId, necessity);
        this.categorySource = CategorySource.AUTO;
    }

    private void applyCategory(UUID categoryId, UUID subcategoryId, Necessity necessity) {
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
        this.subcategoryId = subcategoryId;
        this.necessity = Objects.requireNonNull(necessity, "necessity");
    }

    /**
     * Sets the merchant.
     *
     * @param merchant the merchant, or null
     */
    public void setMerchant(String merchant) {
        this.merchant = merchant;
    }

    /**
     * Changes the amount. Manual expenses only — the service enforces that; the DB moves the row to the new
     * month's partition.
     *
     * @param amount the new amount
     */
    public void reprice(Money amount) {
        this.amount = amount.amount();
        this.currency = amount.currency();
    }

    /**
     * Moves the expense to another date.
     *
     * @param date the new date
     */
    public void redate(LocalDate date) {
        this.date = Objects.requireNonNull(date, "date");
    }

    /**
     * Includes or excludes the expense from spending totals.
     *
     * @param excludedFromHabits true to exclude
     */
    public void setExcludedFromHabits(boolean excludedFromHabits) {
        this.excludedFromHabits = excludedFromHabits;
    }

    /** @return the accounting date */
    public LocalDate getDate() {
        return date;
    }

    /** @return the owner's id */
    public UUID getUserId() {
        return userId;
    }

    /** @return the source transaction id, or null for a manual expense */
    public UUID getTransactionId() {
        return transactionId;
    }

    /** @return the parent expense id for a split part, otherwise null */
    public UUID getParentExpenseId() {
        return parentExpenseId;
    }

    /** @return the free-text note, or null */
    public String getNote() {
        return note;
    }

    /** @return the amount with its currency */
    public Money getAmount() {
        return Money.of(amount, currency);
    }

    /** @return the category id */
    public UUID getCategoryId() {
        return categoryId;
    }

    /** @return the subcategory id, or null */
    public UUID getSubcategoryId() {
        return subcategoryId;
    }

    /** @return the merchant, or null */
    public String getMerchant() {
        return merchant;
    }

    /** @return how necessary the spend was */
    public Necessity getNecessity() {
        return necessity;
    }

    /** @return where the expense came from */
    public ExpenseOrigin getOrigin() {
        return origin;
    }

    /** @return true when the expense is left out of spending totals */
    public boolean isExcludedFromHabits() {
        return excludedFromHabits;
    }

    /** @return whether the system or the user picked the category */
    public CategorySource getCategorySource() {
        return categorySource;
    }
}
