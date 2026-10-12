package com.rohit.nyvra.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.ingestion.AccountSnapshotIngested;
import com.rohit.nyvra.ingestion.PayloadType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test of the ingestion → accounts → net worth path for account snapshots, against a real
 * PostgreSQL: publishing {@link AccountSnapshotIngested} creates or refreshes the account, is idempotent, never
 * rewinds to an older figure, and the user's net-worth snapshot follows.
 */
class IngestedSnapshotWriterIntegrationTest extends AbstractIntegrationTest {

    /** Publisher the test uses to deliver the event the way ingestion does. */
    @Autowired
    private ApplicationEventPublisher publisher;

    /** Direct SQL for fixtures and assertions. */
    @Autowired
    private JdbcTemplate jdbc;

    /** Account persistence, to read the masked number the database stores encrypted. */
    @Autowired
    private FinancialAccountRepository accounts;

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
     * Builds a snapshot event.
     *
     * @param user   owner
     * @param ref    account reference
     * @param type   payload type
     * @param amount amount
     * @param asOf   figure time
     * @return the event
     */
    private static AccountSnapshotIngested snapshot(UUID user, String ref, PayloadType type, String amount, Instant asOf) {
        return new AccountSnapshotIngested(user, UUID.randomUUID(), "FIP", ref, type, new BigDecimal(amount), asOf);
    }

    /**
     * Counts the user's accounts.
     *
     * @param user owner
     * @return the count
     */
    private int accountCount(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM financial_account WHERE user_id = ?", Integer.class, user);
    }

    /** Assets and liabilities become typed AA accounts and the net-worth snapshot counts each on its own side. */
    @Test
    void createsAssetAndLiabilityAccountsAndUpdatesNetWorth() {
        UUID user = newUser();
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        publisher.publishEvent(snapshot(user, "FD-1", PayloadType.TERM_DEPOSIT, "100000.00", t));
        publisher.publishEvent(snapshot(user, "HL-1", PayloadType.LOAN, "30000.00", t));
        publisher.publishEvent(snapshot(user, "CC-1", PayloadType.CREDIT_CARD, "2000.00", t));

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT type, source FROM financial_account WHERE user_id = ? ORDER BY type", user);
        assertThat(rows).extracting(r -> r.get("type")).containsExactly("CREDIT_CARD", "LOAN", "TERM_DEPOSIT");
        assertThat(rows).allMatch(r -> "AA".equals(r.get("source")));

        Map<String, Object> nw = jdbc.queryForMap(
            "SELECT total_assets, total_liabilities FROM net_worth_snapshot WHERE user_id = ? ORDER BY as_of DESC LIMIT 1",
            user);
        assertThat((BigDecimal) nw.get("total_assets")).isEqualByComparingTo("100000.00");
        assertThat((BigDecimal) nw.get("total_liabilities")).isEqualByComparingTo("32000.00");
    }

    /** Replaying a snapshot changes nothing, and a newer one moves the same account's balance. */
    @Test
    void isIdempotentAndRefreshesTheSameAccount() {
        UUID user = newUser();
        AccountSnapshotIngested first = snapshot(user, "EPF-1", PayloadType.EPF, "500.00",
            Instant.parse("2026-09-01T00:00:00Z"));
        publisher.publishEvent(first);
        publisher.publishEvent(first);
        assertThat(accountCount(user)).isEqualTo(1);

        publisher.publishEvent(snapshot(user, "EPF-1", PayloadType.EPF, "650.00", Instant.parse("2026-10-01T00:00:00Z")));

        assertThat(accountCount(user)).isEqualTo(1);
        assertThat(accounts.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(user).get(0).getCurrentBalance().amount())
            .isEqualByComparingTo("650.00");
    }

    /** A snapshot older than the stored balance is ignored. */
    @Test
    void olderSnapshotDoesNotRewindTheBalance() {
        UUID user = newUser();
        publisher.publishEvent(snapshot(user, "NPS-1", PayloadType.NPS, "900.00", Instant.parse("2026-10-01T00:00:00Z")));
        publisher.publishEvent(snapshot(user, "NPS-1", PayloadType.NPS, "100.00", Instant.parse("2026-08-01T00:00:00Z")));

        assertThat(accountCount(user)).isEqualTo(1);
        assertThat(accounts.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(user).get(0).getCurrentBalance().amount())
            .isEqualByComparingTo("900.00");
    }
}
