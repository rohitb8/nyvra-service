package com.rohit.nyvra.analytics;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the append-only {@code health_score} hypertable. Plain JDBC rather than JPA: the table
 * has a composite {@code (user_id, as_of)} key, no surrogate id and no audit columns, and rows are only
 * ever inserted and read.
 */
@Repository
public class HealthScoreRepository {

    /** Sub-scores keyed by their JSON name, as stored in the {@code sub_scores} column. */
    private static final TypeReference<Map<String, HealthScoreResult.SubScore>> SUB_SCORES_TYPE =
        new TypeReference<>() { };
    /** The shape of the {@code top_drivers} column. */
    private static final TypeReference<List<String>> STRINGS_TYPE = new TypeReference<>() { };

    /** Column list shared by the selects. */
    private static final String COLUMNS = """
        as_of, overall, band, sub_scores::text AS sub_scores, confidence, inputs_hash,
        top_drivers::text AS top_drivers, cap_value, cap_reason, ruleset_version, score_spec_version""";

    /** JDBC access. */
    private final JdbcTemplate jdbc;
    /** Serialises the JSON columns. */
    private final ObjectMapper json;

    /**
     * Creates the repository.
     *
     * @param jdbc the JDBC template
     * @param json the shared JSON mapper
     */
    public HealthScoreRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** One bucketed point of the score trend. */
    public record HistoryPoint(Instant asOf, BigDecimal overall, ScoreBand band) {
    }

    /**
     * Finds the user's most recent stored score.
     *
     * @param userId the owner
     * @return the latest score, or empty if none was ever stored
     */
    public Optional<HealthScoreResult> findLatest(UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM health_score WHERE user_id = ? ORDER BY as_of DESC LIMIT 1",
            (rs, n) -> read(rs), userId).stream().findFirst();
    }

    /**
     * Appends a score; an existing row with the same user and instant is left untouched.
     *
     * @param userId the owner
     * @param result a numeric (non-insufficient) score
     */
    public void insert(UUID userId, HealthScoreResult result) {
        Map<String, HealthScoreResult.SubScore> subScores = new LinkedHashMap<>();
        result.subScores().forEach((k, v) -> subScores.put(k.json(), v));
        jdbc.update("""
            INSERT INTO health_score (user_id, as_of, overall, band, sub_scores, confidence, inputs_hash,
                                      top_drivers, cap_value, cap_reason, ruleset_version, score_spec_version)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (user_id, as_of) DO NOTHING""",
            userId, Timestamp.from(result.asOf()), result.overall(), result.band().name(), write(subScores),
            result.confidence(), result.inputsHash(), write(result.topDrivers()),
            result.cap() == null ? null : result.cap().value(),
            result.cap() == null ? null : result.cap().reason().name(),
            result.rulesetVersion(), result.scoreSpecVersion());
    }

    /**
     * Reads the trend: the last score in each bucket, oldest first.
     *
     * @param userId      the owner
     * @param from        inclusive start
     * @param toExclusive exclusive end
     * @param bucket      a {@code date_trunc} unit: {@code day}, {@code week} or {@code month}
     * @param zone        the time zone that decides where a bucket starts, e.g. {@code Asia/Kolkata}
     * @return one point per non-empty bucket
     */
    public List<HistoryPoint> history(UUID userId, Instant from, Instant toExclusive, String bucket, String zone) {
        return jdbc.query("""
            SELECT as_of, overall, band FROM (
                SELECT DISTINCT ON (bucket) as_of, overall, band
                FROM (SELECT as_of, overall, band, date_trunc(?, as_of AT TIME ZONE ?) AS bucket
                      FROM health_score
                      WHERE user_id = ? AND as_of >= ? AND as_of < ?) bucketed
                ORDER BY bucket, as_of DESC) last_per_bucket
            ORDER BY as_of""",
            (rs, n) -> new HistoryPoint(rs.getTimestamp("as_of").toInstant(), rs.getBigDecimal("overall"),
                ScoreBand.valueOf(rs.getString("band"))),
            bucket, zone, userId, Timestamp.from(from), Timestamp.from(toExclusive));
    }

    /**
     * Rebuilds a stored row.
     *
     * @param rs the positioned result set
     * @return the score it holds
     * @throws java.sql.SQLException on read failure
     */
    private HealthScoreResult read(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, HealthScoreResult.SubScore> stored = parse(rs.getString("sub_scores"), SUB_SCORES_TYPE);
        Map<SubScoreKey, HealthScoreResult.SubScore> subScores = new EnumMap<>(SubScoreKey.class);
        stored.forEach((k, v) -> subScores.put(SubScoreKey.fromJson(k), v));
        String drivers = rs.getString("top_drivers");
        String capReason = rs.getString("cap_reason");
        HealthScoreResult.Cap cap = capReason == null ? null
            : new HealthScoreResult.Cap(rs.getBigDecimal("cap_value"), CapReason.valueOf(capReason));
        return new HealthScoreResult(rs.getTimestamp("as_of").toInstant(), rs.getBigDecimal("overall"),
            ScoreBand.valueOf(rs.getString("band")), rs.getBigDecimal("confidence"), subScores,
            drivers == null ? List.of() : parse(drivers, STRINGS_TYPE), cap, List.of(),
            rs.getString("ruleset_version"), rs.getString("score_spec_version"), rs.getString("inputs_hash"));
    }

    /**
     * Serialises a value for a JSONB column.
     *
     * @param value the value
     * @return its JSON text
     */
    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise health score JSON", e);
        }
    }

    /**
     * Parses a JSONB column.
     *
     * @param text the JSON text
     * @param type the target type
     * @param <T>  the target type
     * @return the parsed value
     */
    private <T> T parse(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt health score JSON", e);
        }
    }
}
