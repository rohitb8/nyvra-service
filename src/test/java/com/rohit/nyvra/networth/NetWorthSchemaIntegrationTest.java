package com.rohit.nyvra.networth;

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

/** Exercises the V11 Net Worth DDL directly — the module has no entities or repositories yet. */
class NetWorthSchemaIntegrationTest extends AbstractIntegrationTest {

    /** Raw SQL access, since there are no entities to go through. */
    @Autowired
    private JdbcTemplate jdbc;

    /** Used to create the owning user rows the snapshots reference. */
    @Autowired
    private UserProfileRepository users;

    /** {@code net_worth_snapshot} must be a TimescaleDB hypertable from creation. */
    @Test
    void netWorthSnapshotIsAHypertable() {
        var hypertables = jdbc.queryForList(
            "SELECT hypertable_name FROM timescaledb_information.hypertables", String.class);
        assertThat(hypertables).contains("net_worth_snapshot");
    }

    /** The CHECK constraint ties net worth to assets minus liabilities, and the PK blocks duplicates. */
    @Test
    void snapshotEnforcesNetWorthArithmeticAndUniqueness() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        String sql = "INSERT INTO net_worth_snapshot (user_id, as_of, total_assets, total_liabilities, net_worth) "
            + "VALUES (?, TIMESTAMPTZ '2025-06-01 00:00:00+00', 1000.00, 400.00, ";

        jdbc.update(sql + "600.00)", user.getId());

        assertThatThrownBy(() -> jdbc.update(sql + "600.00)", user.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO net_worth_snapshot (user_id, as_of, total_assets, total_liabilities, net_worth) "
                + "VALUES (?, TIMESTAMPTZ '2025-06-02 00:00:00+00', 1000.00, 400.00, 999.00)", user.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Manual entries accept valid kinds and classes and reject unknown values and negative amounts. */
    @Test
    void manualAssetLiabilityValidatesKindClassAndValue() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());

        insertManual(user.getId(), "ASSET", "REAL_ESTATE", "5000000.00");
        insertManual(user.getId(), "LIABILITY", "PERSONAL_LOAN", "20000.00");

        assertThatThrownBy(() -> insertManual(user.getId(), "EQUITY", "OTHER", "1.00"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertManual(user.getId(), "ASSET", "CRYPTO", "1.00"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertManual(user.getId(), "ASSET", "VEHICLE", "-1.00"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Inserts one {@code manual_asset_liability} row.
     *
     * @param userId owning user
     * @param kind   {@code ASSET} or {@code LIABILITY}
     * @param cls    asset class value
     * @param value  amount as a decimal literal
     */
    private void insertManual(UUID userId, String kind, String cls, String value) {
        jdbc.update("INSERT INTO manual_asset_liability (id, user_id, kind, class, value, value_as_of) "
            + "VALUES (?, ?, ?, ?, " + value + ", DATE '2025-06-01')", UUID.randomUUID(), userId, kind, cls);
    }
}
