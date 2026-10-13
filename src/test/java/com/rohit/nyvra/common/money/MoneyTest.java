package com.rohit.nyvra.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link Money}: scale, validation and currency-safe arithmetic. */
class MoneyTest {

    /** An amount with fewer decimals is padded to scale 2. */
    @Test
    void normalisesToScaleTwo() {
        assertThat(Money.inr("12.5").amount()).isEqualTo(new BigDecimal("12.50"));
    }

    /** Three decimals throw {@link ArithmeticException} rather than being rounded. */
    @Test
    void rejectsMoreThanTwoDecimalsInsteadOfRounding() {
        assertThatThrownBy(() -> Money.inr("1.005")).isInstanceOf(ArithmeticException.class);
    }

    /** A lower-case currency code is rejected with {@link IllegalArgumentException}. */
    @Test
    void rejectsMalformedCurrencyCode() {
        assertThatThrownBy(() -> Money.of("1.00", "inr")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Addition and subtraction in the same currency give the exact scale-2 result. */
    @Test
    void addsAndSubtractsInTheSameCurrency() {
        Money total = Money.inr("100.10").plus(Money.inr("0.90")).minus(Money.inr("1.00"));

        assertThat(total).isEqualTo(Money.inr("100.00"));
    }

    /** Adding INR and USD throws with a "Currency mismatch" message. */
    @Test
    void refusesArithmeticAcrossCurrencies() {
        assertThatThrownBy(() -> Money.inr("1.00").plus(Money.of("1.00", "USD")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Currency mismatch");
    }
}
