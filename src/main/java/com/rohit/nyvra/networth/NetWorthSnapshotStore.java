package com.rohit.nyvra.networth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.rohit.nyvra.common.money.Money;

/**
 * Reads and writes {@code net_worth_snapshot}, the append-only time series behind the net-worth trend. It uses
 * plain SQL because the table has a composite key, JSONB columns and a bucketed read that JPA does not model
 * well, and the table is a TimescaleDB hypertable.
 */
@Repository
public class NetWorthSnapshotStore {

    /** The time zone in which history buckets (day, week, month) are cut. */
    static final String DISPLAY_ZONE = "Asia/Kolkata";

    /** SQL access. */
    private final JdbcTemplate jdbc;
    /** Serialises the JSONB columns. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the store.
     *
     * @param jdbc         the JDBC template
     * @param objectMapper the JSON mapper
     */
    public NetWorthSnapshotStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * One stored point of the series.
     *
     * @param asOf             the start of the snapshot day
     * @param totalAssets      total assets, scale 2
     * @param totalLiabilities total liabilities, scale 2
     * @param netWorth         assets minus liabilities, scale 2
     */
    public record Point(Instant asOf, Money totalAssets, Money totalLiabilities, Money netWorth) {
    }

    /**
     * Writes the snapshot for a day, replacing any earlier one for the same day so a recompute never creates a
     * second row.
     *
     * @param userId      the owner
     * @param asOf        the start of the snapshot day
     * @param computation the valued net worth
     */
    public void upsert(UUID userId, Instant asOf, NetWorthComputation computation) {
        jdbc.update("""
            INSERT INTO net_worth_snapshot
                (user_id, as_of, total_assets, total_liabilities, net_worth, breakdown, contributing_sources)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (user_id, as_of) DO UPDATE SET
                total_assets = EXCLUDED.total_assets,
                total_liabilities = EXCLUDED.total_liabilities,
                net_worth = EXCLUDED.net_worth,
                breakdown = EXCLUDED.breakdown,
                contributing_sources = EXCLUDED.contributing_sources
            """,
            userId, Timestamp.from(asOf),
            computation.totalAssets().amount(), computation.totalLiabilities().amount(),
            computation.netWorth().amount(),
            json(computation.lines().stream()
                .map(l -> Map.of("class", l.netWorthClass().name(), "amount", l.amount().amount().toPlainString()))
                .toList()),
            json(computation.sources().stream().map(NetWorthSnapshotStore::sourceJson).toList()));
    }

    /**
     * Reads the series, keeping the last snapshot in each bucket.
     *
     * @param userId      the owner
     * @param from        inclusive start
     * @param to          exclusive end
     * @param granularity the bucket size
     * @return the points, oldest first
     */
    public List<Point> history(UUID userId, Instant from, Instant to, Granularity granularity) {
        // The unit comes from an enum, never from the request, so inlining it is safe and keeps the DISTINCT ON
        // and ORDER BY expressions identical, which PostgreSQL requires.
        String bucket = "date_trunc('" + granularity.sqlUnit() + "', as_of AT TIME ZONE '" + DISPLAY_ZONE + "')";
        return jdbc.query("SELECT DISTINCT ON (" + bucket + ") as_of, total_assets, total_liabilities, net_worth "
                + "FROM net_worth_snapshot WHERE user_id = ? AND as_of >= ? AND as_of < ? "
                + "ORDER BY " + bucket + ", as_of DESC",
            (rs, i) -> new Point(rs.getTimestamp("as_of").toInstant(),
                Money.of(rs.getBigDecimal("total_assets"), Money.INR),
                Money.of(rs.getBigDecimal("total_liabilities"), Money.INR),
                Money.of(rs.getBigDecimal("net_worth"), Money.INR)),
            userId, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * Builds the JSON form of a source contribution.
     *
     * @param s the contribution
     * @return an ordered map ready to serialise
     */
    private static Map<String, Object> sourceJson(NetWorthComputation.SourceTotal s) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("source", s.source().name());
        json.put("assets", s.assets().amount().toPlainString());
        json.put("liabilities", s.liabilities().amount().toPlainString());
        json.put("itemCount", s.itemCount());
        return json;
    }

    /**
     * Serialises a value for a JSONB column.
     *
     * @param value the value
     * @return its JSON text
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise snapshot JSON", e);
        }
    }
}
