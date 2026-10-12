package com.rohit.nyvra.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.ingestion.HoldingsIngested;
import com.rohit.nyvra.ingestion.IngestedHolding;
import com.rohit.nyvra.ingestion.PayloadType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test of the ingestion → portfolio → net worth path for holdings, against a real PostgreSQL:
 * publishing {@link HoldingsIngested} creates instruments, AA positions and quotes, is idempotent, follows
 * changed units, leaves manual positions alone, and the user's net-worth snapshot follows.
 */
class IngestedHoldingsWriterIntegrationTest extends AbstractIntegrationTest {

    /** Publisher the test uses to deliver the event the way ingestion does. */
    @Autowired
    private ApplicationEventPublisher publisher;

    /** Direct SQL for fixtures and assertions. */
    @Autowired
    private JdbcTemplate jdbc;

    /** Instrument store, to prepare an existing instrument. */
    @Autowired
    private InstrumentRepository instruments;

    /** Holding store, to prepare a manual position. */
    @Autowired
    private PortfolioHoldingRepository holdings;

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
     * Generates an ISIN-shaped code that is unique per call.
     *
     * @return a 12-character code
     */
    private static String isin() {
        return "IN" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    /**
     * Builds a holdings event with one mutual-fund holding.
     *
     * @param user  owner
     * @param isin  ISIN
     * @param units units held
     * @param price NAV
     * @return the event
     */
    private static HoldingsIngested event(UUID user, String isin, String units, String price) {
        BigDecimal u = new BigDecimal(units);
        BigDecimal p = new BigDecimal(price);
        return new HoldingsIngested(user, UUID.randomUUID(), "CAMS", "FOLIO-1", PayloadType.MUTUAL_FUND,
            List.of(new IngestedHolding(isin, "Some Fund", u, p, u.multiply(p).setScale(2, java.math.RoundingMode.HALF_UP))));
    }

    /** A new fund creates the instrument, an AA position and a quote, and net worth counts the value. */
    @Test
    void createsInstrumentPositionAndQuote() {
        UUID user = newUser();
        String isin = isin();

        publisher.publishEvent(event(user, isin, "10.000000", "50.000000"));

        Map<String, Object> position = jdbc.queryForMap(
            "SELECT h.quantity, h.source, h.asset_class FROM portfolio_holding h JOIN instrument i ON i.id = h.instrument_id "
                + "WHERE h.user_id = ? AND i.isin = ?", user, isin);
        assertThat((BigDecimal) position.get("quantity")).isEqualByComparingTo("10");
        assertThat(position.get("source")).isEqualTo("AA");
        assertThat(position.get("asset_class")).isEqualTo("MUTUAL_FUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM price_quote q JOIN instrument i ON i.id = q.instrument_id "
            + "WHERE i.isin = ?", Integer.class, isin)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT total_assets FROM net_worth_snapshot WHERE user_id = ? ORDER BY as_of DESC LIMIT 1",
            BigDecimal.class, user)).isEqualByComparingTo("500.00");
    }

    /** Replays add nothing; changed units update the one position; the day keeps a single quote. */
    @Test
    void isIdempotentAndFollowsChangedUnits() {
        UUID user = newUser();
        String isin = isin();
        HoldingsIngested first = event(user, isin, "10.000000", "50.000000");

        publisher.publishEvent(first);
        publisher.publishEvent(first);
        publisher.publishEvent(event(user, isin, "12.500000", "50.000000"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM portfolio_holding WHERE user_id = ?", Integer.class, user))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM portfolio_holding WHERE user_id = ?", BigDecimal.class, user))
            .isEqualByComparingTo("12.5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM price_quote q JOIN instrument i ON i.id = q.instrument_id "
            + "WHERE i.isin = ?", Integer.class, isin)).isEqualTo(1);
    }

    /** A position the user entered by hand is not changed or duplicated. */
    @Test
    void leavesManualPositionsAlone() {
        UUID user = newUser();
        String isin = isin();
        Instrument instrument = instruments.save(new Instrument(isin, null, "Some Fund", AssetClass.MUTUAL_FUND, "INR", "IN"));
        holdings.save(new PortfolioHolding(user, instrument, new BigDecimal("3"), null, RecordSource.MANUAL, Instant.now()));

        publisher.publishEvent(event(user, isin, "10.000000", "50.000000"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM portfolio_holding WHERE user_id = ?", Integer.class, user))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM portfolio_holding WHERE user_id = ?", BigDecimal.class, user))
            .isEqualByComparingTo("3");
    }
}
