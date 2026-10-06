package com.rohit.nyvra.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalisesToScaleTwo() {
        assertThat(Money.inr("12.5").amount()).isEqualTo(new BigDecimal("12.50"));
    }

    @Test
    void rejectsMoreThanTwoDecimalsInsteadOfRounding() {
        assertThatThrownBy(() -> Money.inr("1.005")).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsMalformedCurrencyCode() {
        assertThatThrownBy(() -> Money.of("1.00", "inr")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addsAndSubtractsInTheSameCurrency() {
        Money total = Money.inr("100.10").plus(Money.inr("0.90")).minus(Money.inr("1.00"));

        assertThat(total).isEqualTo(Money.inr("100.00"));
    }

    @Test
    void refusesArithmeticAcrossCurrencies() {
        assertThatThrownBy(() -> Money.inr("1.00").plus(Money.of("1.00", "USD")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Currency mismatch");
    }
}
