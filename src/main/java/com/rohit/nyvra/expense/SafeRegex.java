package com.rohit.nyvra.expense;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.rohit.nyvra.common.exception.BadRequestException;

/**
 * Compiles and runs user-supplied regular expressions without letting a pathological pattern pin a thread.
 * Matching reads the input through a {@link CharSequence} that aborts once a deadline passes, so a catastrophic
 * backtracking pattern ends as "no match" after {@link #MATCH_TIMEOUT_NANOS} instead of hanging.
 */
final class SafeRegex {

    /** Longest a single match attempt may run. */
    static final long MATCH_TIMEOUT_NANOS = 100_000_000L;

    private SafeRegex() {
    }

    /**
     * Compiles a user regex.
     *
     * @param regex the pattern text
     * @return the compiled pattern
     * @throws BadRequestException if it is not a valid regex
     */
    static Pattern compile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new BadRequestException("matcherValue is not a valid regular expression: " + e.getDescription());
        }
    }

    /**
     * Whether the pattern is found anywhere in the input.
     *
     * @param pattern the compiled pattern
     * @param input   the text to search
     * @return true on a match; false on no match or when matching exceeded the time limit
     */
    static boolean find(Pattern pattern, String input) {
        try {
            return pattern.matcher(new DeadlineCharSequence(input, System.nanoTime() + MATCH_TIMEOUT_NANOS)).find();
        } catch (MatchTimeoutException e) {
            return false;
        }
    }

    /** Thrown from {@link DeadlineCharSequence#charAt} once the deadline has passed. */
    private static final class MatchTimeoutException extends RuntimeException {

        /** Creates the exception without a stack trace; it is control flow. */
        MatchTimeoutException() {
            super("regex match timed out", null, false, false);
        }
    }

    /** A {@link CharSequence} view that fails once {@code deadline} (a {@link System#nanoTime()} value) passes. */
    private static final class DeadlineCharSequence implements CharSequence {

        /** The text being read. */
        private final CharSequence delegate;

        /** {@link System#nanoTime()} after which reads fail. */
        private final long deadline;

        /**
         * Wraps a text.
         *
         * @param delegate the text
         * @param deadline the {@link System#nanoTime()} value after which reads fail
         */
        DeadlineCharSequence(CharSequence delegate, long deadline) {
            this.delegate = delegate;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() - deadline > 0) {
                throw new MatchTimeoutException();
            }
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(delegate.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
