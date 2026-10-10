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

    private UUID categoryId;
    private UUID subcategoryId;
    private boolean subcategoryProvided;
    @Size(max = 120)
    private String merchant;
    private Necessity necessity;
    private Boolean excludedFromHabits;
    @Valid
    private MoneyDto amount;
    private LocalDate date;

    public UUID categoryId() {
        return categoryId;
    }

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

    public String merchant() {
        return merchant;
    }

    public void setMerchant(String merchant) {
        this.merchant = merchant;
    }

    public Necessity necessity() {
        return necessity;
    }

    public void setNecessity(Necessity necessity) {
        this.necessity = necessity;
    }

    public Boolean excludedFromHabits() {
        return excludedFromHabits;
    }

    public void setExcludedFromHabits(Boolean excludedFromHabits) {
        this.excludedFromHabits = excludedFromHabits;
    }

    public MoneyDto amount() {
        return amount;
    }

    public void setAmount(MoneyDto amount) {
        this.amount = amount;
    }

    public LocalDate date() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }
}
