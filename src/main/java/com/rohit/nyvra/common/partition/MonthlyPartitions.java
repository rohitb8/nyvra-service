package com.rohit.nyvra.common.partition;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates monthly range partitions for the partitioned tables ({@code transaction}, {@code expense}) via
 * the {@code ensure_monthly_partition} SQL function (V2). Idempotent; rows already sitting in the
 * table's {@code DEFAULT} partition for that month are moved into the new partition.
 *
 * <p>Writers of historical data (an AA fetch can span years) call {@link #ensureRange} for the months
 * they are about to write before inserting; {@link MonthlyRangePartitionMaintenance} keeps the months
 * ahead covered. See {@code database/decisions.md} §2.
 */
@Component
public class MonthlyPartitions {

    /** Allow-list: the function takes a regclass, but nothing outside these tables should reach it. */
    static final Set<String> PARTITIONED_TABLES = Set.of("transaction", "expense");

    /** JDBC access used to call the SQL function. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the component.
     *
     * @param jdbcTemplate JDBC template for the SQL function call
     */
    public MonthlyPartitions(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Ensures the partition covering one month exists, creating it if needed.
     *
     * @param table partitioned table name; must be {@code transaction} or {@code expense}
     * @param month the calendar month to cover (UTC month boundaries)
     * @throws IllegalArgumentException if the table is not an allowed partitioned table
     */
    @Transactional
    public void ensureMonth(String table, YearMonth month) {
        if (!PARTITIONED_TABLES.contains(table)) {
            throw new IllegalArgumentException("Not a monthly-partitioned table: " + table);
        }
        jdbcTemplate.queryForObject(
            "SELECT ensure_monthly_partition(?::regclass, ?)::text", String.class, table, month.atDay(1));
    }

    /**
     * Ensures every month from {@code from} to {@code to}, inclusive.
     *
     *
     * @param table partitioned table name; must be {@code transaction} or {@code expense}
     * @param from first date, its month is the first ensured
     * @param to last date, its month is the last ensured
     * @throws IllegalArgumentException if the table is not an allowed partitioned table
     */
    @Transactional
    public void ensureRange(String table, LocalDate from, LocalDate to) {
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            ensureMonth(table, month);
        }
    }
}
