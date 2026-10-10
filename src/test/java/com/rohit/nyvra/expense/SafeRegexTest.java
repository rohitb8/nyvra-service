package com.rohit.nyvra.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;

import com.rohit.nyvra.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link SafeRegex}: compiling user patterns and bounding how long a match may run. */
class SafeRegexTest {

    /** A valid pattern compiles and is searched anywhere in the input. */
    @Test
    void findsAValidPatternAnywhereInTheInput() {
        Pattern pattern = SafeRegex.compile("(?i)swiggy|zomato");

        assertThat(SafeRegex.find(pattern, "UPI-SWIGGY-1234")).isTrue();
        assertThat(SafeRegex.find(pattern, "Big Bazaar")).isFalse();
    }

    /** An invalid pattern is reported as a 400-class error, not a raw regex exception. */
    @Test
    void rejectsAnInvalidPattern() {
        assertThatThrownBy(() -> SafeRegex.compile("(unclosed"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("not a valid regular expression");
    }

    /** A catastrophically backtracking pattern gives up as "no match" well before it could hang a thread. */
    @Test
    void givesUpOnCatastrophicBacktracking() {
        Pattern evil = SafeRegex.compile("(a+)+$");
        String input = "a".repeat(40) + "b";

        long start = System.nanoTime();
        boolean found = SafeRegex.find(evil, input);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(found).isFalse();
        assertThat(elapsedMillis).isLessThan(5_000);
    }
}
