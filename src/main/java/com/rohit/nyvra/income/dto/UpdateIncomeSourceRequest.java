package com.rohit.nyvra.income.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.IncomeCadence;
import com.rohit.nyvra.income.IncomeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Omitted fields are unchanged. {@code expectedAmount: null} clears the amount, which differs from
 * omitting it, so this is a class that records whether the property was sent.
 */
public class UpdateIncomeSourceRequest {

    @Pattern(regexp = ".*\\S.*", message = "must not be blank")
    @Size(max = 80)
    private String name;
    private IncomeType type;
    private IncomeCadence cadence;
    @Valid
    private MoneyDto expectedAmount;
    private boolean expectedAmountPresent;
    private Boolean active;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public IncomeType getType() {
        return type;
    }

    public void setType(IncomeType type) {
        this.type = type;
    }

    public IncomeCadence getCadence() {
        return cadence;
    }

    public void setCadence(IncomeCadence cadence) {
        this.cadence = cadence;
    }

    public MoneyDto getExpectedAmount() {
        return expectedAmount;
    }

    @JsonSetter("expectedAmount")
    public void setExpectedAmount(MoneyDto expectedAmount) {
        this.expectedAmount = expectedAmount;
        this.expectedAmountPresent = true;
    }

    public boolean isExpectedAmountPresent() {
        return expectedAmountPresent;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
