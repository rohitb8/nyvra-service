package com.rohit.nyvra.common.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.accounts.AccountTransaction;
import com.rohit.nyvra.accounts.AccountTransactionRepository;
import com.rohit.nyvra.accounts.AccountType;
import com.rohit.nyvra.accounts.FinancialAccount;
import com.rohit.nyvra.accounts.FinancialAccountRepository;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;

class MonthlyPartitionsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MonthlyPartitions partitions;

    @Autowired
    private UserProfileRepository users;

    @Autowired
    private FinancialAccountRepository accounts;

    @Autowired
    private AccountTransactionRepository transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationsPreCreateTheCurrentMonthAndAFewAhead() {
        YearMonth now = YearMonth.now();

        assertThat(partitionExists("transaction_" + suffix(now))).isTrue();
        assertThat(partitionExists("transaction_" + suffix(now.plusMonths(3)))).isTrue();
        assertThat(partitionExists("expense_" + suffix(now.plusMonths(3)))).isTrue();
    }

    @Test
    void movesRowsOutOfTheDefaultPartitionWhenTheirMonthIsCreated() {
        // A month nobody else touches, so this test owns its partition.
        LocalDate historic = LocalDate.of(2003, 2, 14);
        AccountTransaction row = transactions.save(aTransaction(historic));
        assertThat(partitionOf(row)).isEqualTo("transaction_default");

        partitions.ensureMonth("transaction", YearMonth.from(historic));

        assertThat(partitionOf(row)).isEqualTo("transaction_2003_02");
        assertThat(transactions.findById(row.getId())).isPresent();
    }

    @Test
    void isIdempotent() {
        YearMonth month = YearMonth.of(2004, 7);

        partitions.ensureMonth("expense", month);
        partitions.ensureMonth("expense", month);

        assertThat(partitionExists("expense_2004_07")).isTrue();
    }

    @Test
    void refusesTablesOutsideTheAllowList() {
        assertThatThrownBy(() -> partitions.ensureMonth("user_profile", YearMonth.now()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private AccountTransaction aTransaction(LocalDate valueDate) {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        FinancialAccount account = accounts.save(new FinancialAccount(user.getId(), AccountType.SAVINGS,
            "HDFC Bank", "XXXX1234", "Salary", Money.inr("0.00"), Instant.now(), RecordSource.MANUAL));
        return new AccountTransaction(account.getId(), user.getId(), valueDate, valueDate, Money.inr("-10.00"),
            "narration", null, null, RecordSource.MANUAL, "dedup-" + account.getId());
    }

    private String partitionOf(AccountTransaction row) {
        return jdbcTemplate.queryForObject(
            "SELECT tableoid::regclass::text FROM transaction WHERE id = ?", String.class, row.getId());
    }

    private boolean partitionExists(String name) {
        return jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name);
    }

    private static String suffix(YearMonth month) {
        return "%d_%02d".formatted(month.getYear(), month.getMonthValue());
    }
}
