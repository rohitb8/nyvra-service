package com.rohit.nyvra.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;

/** Exercises the V6 Portfolio DDL directly — the module has no entities or repositories yet. */
class PortfolioSchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserProfileRepository users;

    @Test
    void priceQuoteAndValuationSnapshotAreHypertables() {
        var hypertables = jdbc.queryForList(
            "SELECT hypertable_name FROM timescaledb_information.hypertables", String.class);
        assertThat(hypertables).contains("price_quote", "valuation_snapshot");
    }

    @Test
    void storesPricesAndRejectsDuplicatesAndNegatives() {
        UUID instrument = anInstrument("INE002A01018");
        jdbc.update("INSERT INTO price_quote (instrument_id, as_of, price, source) VALUES (?, now(), 2850.123456, 'MANUAL')",
            instrument);

        assertThat(jdbc.queryForObject("SELECT price FROM price_quote WHERE instrument_id = ?",
            java.math.BigDecimal.class, instrument)).isEqualByComparingTo("2850.123456");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO price_quote (instrument_id, as_of, price, source) VALUES (?, now() - interval '1 day', -1, 'MANUAL')",
            instrument)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void holdingMustHaveZeroQuantityToBeClosed() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        UUID instrument = anInstrument("INE009A01021");

        assertThatThrownBy(() -> insertHolding(user.getId(), instrument, "10", "now()"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertHolding(user.getId(), instrument, "-1", "NULL"))
            .isInstanceOf(DataIntegrityViolationException.class);
        insertHolding(user.getId(), instrument, "0", "now()");
        insertHolding(user.getId(), instrument, "10.5", "NULL");
    }

    @Test
    void corporateActionsAreUniquePerInstrumentTypeAndDate() {
        UUID instrument = anInstrument("INE040A01034");
        String sql = "INSERT INTO corporate_action (id, instrument_id, type, ex_date, ratio) "
            + "VALUES (?, ?, 'SPLIT', DATE '2025-06-01', 2)";
        jdbc.update(sql, UUID.randomUUID(), instrument);

        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), instrument))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void valuationSnapshotIsKeyedByUserAndTime() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        String sql = "INSERT INTO valuation_snapshot (user_id, as_of, by_asset_class, total_value) "
            + "VALUES (?, TIMESTAMPTZ '2025-06-01 00:00:00+00', '{\"EQUITY\": 100.00}'::jsonb, 100.00)";
        jdbc.update(sql, user.getId());

        assertThatThrownBy(() -> jdbc.update(sql, user.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID anInstrument(String isin) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO instrument (id, isin, symbol, name, asset_class, currency, country) "
            + "VALUES (?, ?, 'SYM', 'Test instrument', 'EQUITY', 'INR', 'IN')", id, isin);
        return id;
    }

    private void insertHolding(UUID userId, UUID instrumentId, String quantity, String closedAt) {
        jdbc.update("INSERT INTO portfolio_holding (id, user_id, instrument_id, asset_class, quantity, currency, source, closed_at) "
            + "VALUES (?, ?, ?, 'EQUITY', " + quantity + ", 'INR', 'MANUAL', " + closedAt + ")",
            UUID.randomUUID(), userId, instrumentId);
    }
}
