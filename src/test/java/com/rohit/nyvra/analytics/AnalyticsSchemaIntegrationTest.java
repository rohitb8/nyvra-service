package com.rohit.nyvra.analytics;

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

/** Exercises the V12 Analytics DDL directly — the module has no entities or repositories yet. */
class AnalyticsSchemaIntegrationTest extends AbstractIntegrationTest {

    /** Raw SQL access, since there are no entities to go through. */
    @Autowired
    private JdbcTemplate jdbc;

    /** Used to create the owning user rows. */
    @Autowired
    private UserProfileRepository users;

    /** {@code health_score} must be a TimescaleDB hypertable from creation. */
    @Test
    void healthScoreIsAHypertable() {
        var hypertables = jdbc.queryForList(
            "SELECT hypertable_name FROM timescaledb_information.hypertables", String.class);
        assertThat(hypertables).contains("health_score");
    }

    /** The overall score is bounded to 0..100 and the band to the known values. */
    @Test
    void healthScoreRejectsOutOfRangeScoreAndUnknownBand() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());

        insertScore(user.getId(), "2025-06-01", "72.50", "GOOD");

        assertThatThrownBy(() -> insertScore(user.getId(), "2025-06-02", "100.01", "GOOD"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertScore(user.getId(), "2025-06-03", "50", "AMAZING"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A user may hold only one active insight per rule, but a superseded one frees the slot. */
    @Test
    void onlyOneActiveInsightPerRule() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        UUID first = insertInsight(user.getId(), "LOW_EMERGENCY_FUND");

        assertThatThrownBy(() -> insertInsight(user.getId(), "LOW_EMERGENCY_FUND"))
            .isInstanceOf(DataIntegrityViolationException.class);
        insertInsight(user.getId(), "HIGH_CARD_UTILISATION");

        jdbc.update("UPDATE insight SET superseded_at = now() WHERE id = ?", first);
        insertInsight(user.getId(), "LOW_EMERGENCY_FUND");
    }

    /** The dashboard cache holds a single row per user. */
    @Test
    void dashboardCacheIsOneRowPerUser() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        String sql = "INSERT INTO dashboard_summary_cache (user_id, payload) VALUES (?, '{}'::jsonb)";
        jdbc.update(sql, user.getId());

        assertThatThrownBy(() -> jdbc.update(sql, user.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Inserts one {@code health_score} row.
     *
     * @param userId owning user
     * @param day    ISO date used as the snapshot time (UTC midnight)
     * @param overall overall score literal
     * @param band   band value
     */
    private void insertScore(UUID userId, String day, String overall, String band) {
        jdbc.update("INSERT INTO health_score (user_id, as_of, overall, band, sub_scores) "
            + "VALUES (?, TIMESTAMPTZ '" + day + " 00:00:00+00', " + overall + ", ?, '{}'::jsonb)", userId, band);
    }

    /**
     * Inserts one active {@code insight} row.
     *
     * @param userId owning user
     * @param ruleId rule that raised it
     * @return the new row id
     */
    private UUID insertInsight(UUID userId, String ruleId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO insight (id, user_id, rule_id, severity, message) VALUES (?, ?, ?, 'WARN', 'msg')",
            id, userId, ruleId);
        return id;
    }
}
