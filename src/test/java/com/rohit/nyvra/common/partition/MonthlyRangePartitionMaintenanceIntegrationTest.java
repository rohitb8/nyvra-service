package com.rohit.nyvra.common.partition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.rohit.nyvra.AbstractIntegrationTest;

/** Runs the job with scheduling + ShedLock switched on, as every non-test profile does. */
@TestPropertySource(properties = {"nyvra.jobs.enabled=true", "nyvra.partitions.months-ahead=5"})
class MonthlyRangePartitionMaintenanceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MonthlyRangePartitionMaintenance job;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsPartitionsAheadUnderAShedLock() {
        job.createUpcomingPartitions();

        YearMonth target = YearMonth.now(ZoneOffset.UTC).plusMonths(5);
        String suffix = "%d_%02d".formatted(target.getYear(), target.getMonthValue());
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
            "transaction_" + suffix)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
            "expense_" + suffix)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM shedlock WHERE name = 'monthly-range-partition-maintenance'", Integer.class))
            .isEqualTo(1);
    }
}
