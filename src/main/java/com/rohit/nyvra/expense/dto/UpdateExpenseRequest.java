package com.rohit.nyvra.expense.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.expense.Necessity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Omitted fields are unchanged. A class rather than a record because {@code subcategoryId} is tri-state —
 * absent (keep), explicit JSON {@code null} (clear), or a value (set) — and Jackson cannot tell absent from
 * {@code null} for a record component. Not a {@code record} accessor style on purpose: see
 * {@link #subcategoryProvided()}.
 */
public class UpdateExpenseRequest {

    /** New top-level category, or null to leave unchanged. */
    private UUID categoryId;
    /** New subcategory; null means cleared when {@link #subcategoryProvided} is true. */
    private UUID subcategoryId;
    /** Whether the request carried {@code subcategoryId}, even as an explicit null. */
    private boolean subcategoryProvided;
    /** New merchant, at most 120 characters, or null to leave unchanged. */
    @Size(max = 120)
    private String merchant;
    /** New necessity, or null to leave unchanged. */
    private Necessity necessity;
    /** New habits-exclusion flag, or null to leave unchanged. */
    private Boolean excludedFromHabits;
    /** New amount (manual expenses only), or null to leave unchanged. */
    @Valid
    private MoneyDto amount;
    /** New date (manual expenses only), or null to leave unchanged. */
    private LocalDate date;

    /** @return the new category, or null if unchanged */
    public UUID categoryId() {
        return categoryId;
    }

    /** @param categoryId the new category, or null to leave unchanged */
    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    /** The new subcategory, or {@code null} when it is being cleared (check {@link #subcategoryProvided()}). */
    public UUID subcategoryId() {
        return subcategoryId;
    }

    /** Called by Jackson only when the key is present, including as {@code null}. */
    @JsonProperty("subcategoryId")
    public void setSubcategoryId(UUID subcategoryId) {
        this.subcategoryId = subcategoryId;
        this.subcategoryProvided = true;
    }

    /** Whether the request carried {@code subcategoryId} at all. */
    public boolean subcategoryProvided() {
        return subcategoryProvided;
    }

    /** @return the new merchant, or null if unchanged */
    public String merchant() {
        return merchant;
    }

    /** @param merchant the new merchant, or null to leave unchanged */
    public void setMerchant(String merchant) {
        this.merchant = merchant;
    }

    /** @return the new necessity, or null if unchanged */
    public Necessity necessity() {
        return necessity;
    }

    /** @param necessity the new necessity, or null to leave unchanged */
    public void setNecessity(Necessity necessity) {
        this.necessity = necessity;
    }

    /** @return the new habits-exclusion flag, or null if unchanged */
    public Boolean excludedFromHabits() {
        return excludedFromHabits;
    }

    /** @param excludedFromHabits the new flag, or null to leave unchanged */
    public void setExcludedFromHabits(Boolean excludedFromHabits) {
        this.excludedFromHabits = excludedFromHabits;
    }

    /** @return the new amount, or null if unchanged */
    public MoneyDto amount() {
        return amount;
    }

    /** @param amount the new amount, or null to leave unchanged */
    public void setAmount(MoneyDto amount) {
        this.amount = amount;
    }

    /** @return the new date, or null if unchanged */
    public LocalDate date() {
        return date;
    }

    /** @param date the new date, or null to leave unchanged */
    public void setDate(LocalDate date) {
        this.date = date;
    }
}
