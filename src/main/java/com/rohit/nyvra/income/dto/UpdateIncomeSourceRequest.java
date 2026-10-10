package com.rohit.nyvra.income.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.IncomeCadence;
import com.rohit.nyvra.income.IncomeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for a partial update of an income source. Omitted fields are unchanged.
 * {@code expectedAmount: null} clears the amount, which differs from omitting it, so this is a class that
 * records whether the property was sent.
 */
public class UpdateIncomeSourceRequest {

    /** New label, or {@code null} to keep it; not blank, at most 80 characters. */
    @Pattern(regexp = ".*\\S.*", message = "must not be blank")
    @Size(max = 80)
    private String name;
    /** New type, or {@code null} to keep it. */
    private IncomeType type;
    /** New cadence, or {@code null} to keep it. */
    private IncomeCadence cadence;
    /** New expected amount; {@code null} means clear it when {@link #expectedAmountPresent} is set. */
    @Valid
    private MoneyDto expectedAmount;
    /** Whether the JSON contained {@code expectedAmount}, even as {@code null}. */
    private boolean expectedAmountPresent;
    /** New active flag, or {@code null} to keep it. */
    private Boolean active;

    /** @return the new label, or {@code null} if not sent */
    public String getName() {
        return name;
    }

    /**
     * Sets the new label.
     *
     * @param name the label
     */
    public void setName(String name) {
        this.name = name;
    }

    /** @return the new type, or {@code null} if not sent */
    public IncomeType getType() {
        return type;
    }

    /**
     * Sets the new type.
     *
     * @param type the type
     */
    public void setType(IncomeType type) {
        this.type = type;
    }

    /** @return the new cadence, or {@code null} if not sent */
    public IncomeCadence getCadence() {
        return cadence;
    }

    /**
     * Sets the new cadence.
     *
     * @param cadence the cadence
     */
    public void setCadence(IncomeCadence cadence) {
        this.cadence = cadence;
    }

    /**
      * @return the new expected amount; {@code null} either means not sent or clear, see {@link
      *     #isExpectedAmountPresent()}
     */
    public MoneyDto getExpectedAmount() {
        return expectedAmount;
    }

    /**
     * Sets the new expected amount and records that the property was present in the JSON, so an explicit
     * {@code null} can be told apart from an omitted field.
     *
     * @param expectedAmount the amount, or {@code null} to clear it
     */
    @JsonSetter("expectedAmount")
    public void setExpectedAmount(MoneyDto expectedAmount) {
        this.expectedAmount = expectedAmount;
        this.expectedAmountPresent = true;
    }

    /** @return {@code true} if {@code expectedAmount} was sent, even as {@code null} */
    public boolean isExpectedAmountPresent() {
        return expectedAmountPresent;
    }

    /** @return the new active flag, or {@code null} if not sent */
    public Boolean getActive() {
        return active;
    }

    /**
     * Sets the new active flag.
     *
     * @param active the flag
     */
    public void setActive(Boolean active) {
        this.active = active;
    }
}
