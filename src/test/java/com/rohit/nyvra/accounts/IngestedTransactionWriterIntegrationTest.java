package com.rohit.nyvra.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.expense.CategorisationRule;
import com.rohit.nyvra.expense.CategorisationRuleRepository;
import com.rohit.nyvra.expense.MatcherType;
import com.rohit.nyvra.expense.Necessity;
import com.rohit.nyvra.ingestion.IngestedDirection;
import com.rohit.nyvra.ingestion.IngestedTransaction;
import com.rohit.nyvra.ingestion.TransactionsIngested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test of the ingestion → ledger → expense path against a real PostgreSQL: publishing
 * {@link TransactionsIngested} writes ledger lines, creates and refreshes the account, categorises debits with
 * the user's rules and is idempotent on the dedup key.
 */
class IngestedTransactionWriterIntegrationTest extends AbstractIntegrationTest {

    /** Id of the system {@code Food & dining} category. */
    private static final UUID FOOD = UUID.fromString("40a8ed3d-45f2-50fe-8806-e94e6a8eb931");

    /** Id of the system {@code Other} category. */
    private static final UUID OTHER = UUID.fromString("c8905229-6cc2-591f-a41a-16bed8d61624");

    /** Publisher the test uses to deliver the event the way ingestion does. */
    @Autowired
    private ApplicationEventPublisher publisher;

    /** Direct SQL for fixtures and assertions. */
    @Autowired
    private JdbcTemplate jdbc;

    /** Rule store, to give the user a categorisation rule. */
    @Autowired
    private CategorisationRuleRepository rules;

    /**
     * Inserts a user profile row.
     *
     * @return the new user's id
     */
    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profile (id, keycloak_subject, display_name) VALUES (?, ?, 'T')", id, "sub-" + id);
        return id;
    }

    /**
     * Builds one normalised transaction.
     *
     * @param key       dedup key
     * @param date      value date
     * @param amount    positive amount
     * @param direction debit or credit
     * @param narration bank narration
     * @param balance   balance after, or null
     * @return the transaction
     */
    private static IngestedTransaction txn(String key, LocalDate date, String amount, IngestedDirection direction,
                                           String narration, String balance) {
        return new IngestedTransaction(key, date, new BigDecimal(amount), direction, narration,
            balance == null ? null : new BigDecimal(balance));
    }

    /**
     * Counts the user's ledger lines.
     *
     * @param userId owner
     * @return the count
     */
    private int ledgerCount(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM transaction WHERE user_id = ?", Integer.class, userId);
    }

    /** Debits and credits are ledgered with signs, debits become categorised expenses, and the account appears. */
    @Test
    void writesLedgerCategorisesDebitsAndCreatesAccount() {
        UUID user = newUser();
        rules.save(new CategorisationRule(user, MatcherType.NARRATION_REGEX, "(?i)swiggy", FOOD,
            Necessity.DISCRETIONARY, 10));
        String suffix = user.toString();
        TransactionsIngested event = new TransactionsIngested(user, UUID.randomUUID(), "HDFC", "XXXX" + suffix.substring(0, 6),
            List.of(
                txn("k1-" + suffix, LocalDate.of(2025, 5, 30), "85000", IngestedDirection.CREDIT, "SALARY", "90000.00"),
                txn("k2-" + suffix, LocalDate.of(2025, 5, 31), "250.50", IngestedDirection.DEBIT, "UPI-SWIGGY-ORDER", "89749.50"),
                txn("k3-" + suffix, LocalDate.of(2025, 6, 1), "40", IngestedDirection.DEBIT, "UPI-UNKNOWN", "89709.50")));

        publisher.publishEvent(event);

        assertThat(ledgerCount(user)).isEqualTo(3);
        Map<String, Object> debit = jdbc.queryForMap(
            "SELECT amount, direction, source FROM transaction WHERE user_id = ? AND dedup_key = ?", user, "k2-" + suffix);
        assertThat((BigDecimal) debit.get("amount")).isEqualByComparingTo("-250.50");
        assertThat(debit.get("direction")).isEqualTo("DEBIT");
        assertThat(debit.get("source")).isEqualTo("AA");

        List<Map<String, Object>> expenses = jdbc.queryForList(
            "SELECT category_id, amount, origin, necessity, category_source FROM expense WHERE user_id = ? ORDER BY date", user);
        assertThat(expenses).hasSize(2);
        assertThat(expenses.get(0).get("category_id")).isEqualTo(FOOD);
        assertThat(expenses.get(0).get("origin")).isEqualTo("AA");
        assertThat(expenses.get(0).get("category_source")).isEqualTo("AUTO");
        assertThat(expenses.get(1).get("category_id")).isEqualTo(OTHER);

        Map<String, Object> account = jdbc.queryForMap(
            "SELECT type, institution, current_balance, source FROM financial_account WHERE user_id = ?", user);
        assertThat(account.get("type")).isEqualTo("SAVINGS");
        assertThat(account.get("institution")).isEqualTo("HDFC");
        assertThat((BigDecimal) account.get("current_balance")).isEqualByComparingTo("89709.50");
    }

    /** Publishing the same event again writes nothing new, and a later event reuses the account. */
    @Test
    void isIdempotentAndReusesTheAccount() {
        UUID user = newUser();
        String ref = "XXXX" + user.toString().substring(0, 6);
        TransactionsIngested first = new TransactionsIngested(user, UUID.randomUUID(), "ICICI", ref, List.of(
            txn("a-" + user, LocalDate.of(2025, 4, 1), "10", IngestedDirection.DEBIT, "x", "100.00")));

        publisher.publishEvent(first);
        publisher.publishEvent(first);

        assertThat(ledgerCount(user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense WHERE user_id = ?", Integer.class, user)).isEqualTo(1);

        publisher.publishEvent(new TransactionsIngested(user, UUID.randomUUID(), "ICICI", ref, List.of(
            txn("b-" + user, LocalDate.of(2025, 4, 2), "5", IngestedDirection.DEBIT, "y", "95.00"))));

        assertThat(ledgerCount(user)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_account WHERE user_id = ?", Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_balance FROM financial_account WHERE user_id = ?", BigDecimal.class, user))
            .isEqualByComparingTo("95.00");
    }

    /** An older batch does not move the balance backwards. */
    @Test
    void olderTransactionsDoNotRewindTheBalance() {
        UUID user = newUser();
        String ref = "XXXX" + user.toString().substring(0, 6);
        publisher.publishEvent(new TransactionsIngested(user, UUID.randomUUID(), "SBI", ref, List.of(
            txn("n-" + user, LocalDate.of(2025, 6, 10), "1", IngestedDirection.CREDIT, "new", "500.00"))));
        publisher.publishEvent(new TransactionsIngested(user, UUID.randomUUID(), "SBI", ref, List.of(
            txn("o-" + user, LocalDate.of(2025, 1, 10), "1", IngestedDirection.CREDIT, "old", "10.00"))));

        assertThat(ledgerCount(user)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT current_balance FROM financial_account WHERE user_id = ?", BigDecimal.class, user))
            .isEqualByComparingTo("500.00");
    }
}
