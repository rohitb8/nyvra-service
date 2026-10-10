package com.rohit.nyvra.accounts;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.partition.MonthlyPartitions;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.expense.Expense;
import com.rohit.nyvra.expense.ExpenseOrigin;
import com.rohit.nyvra.expense.ExpenseRepository;
import com.rohit.nyvra.expense.Necessity;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@AutoConfigureMockMvc
class TransactionControllerIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate JAN = LocalDate.of(2024, 1, 1);
    private static final LocalDate FEB = LocalDate.of(2024, 2, 1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserProfileRepository users;

    @Autowired
    private FinancialAccountRepository accounts;

    @Autowired
    private AccountTransactionRepository transactions;

    @Autowired
    private MonthlyPartitions partitions;

    @Autowired
    private ExpenseRepository expenses;

    private record Seed(String subject, UserProfile user, FinancialAccount account) {
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }

    private Seed seedUser() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        FinancialAccount account = accounts.save(new FinancialAccount(
            user.getId(), AccountType.SAVINGS, "HDFC", "XXXX1234", "Salary", Money.inr("0.00"),
            Instant.now(), RecordSource.AA));
        return new Seed(user.getKeycloakSubject(), user, account);
    }

    private AccountTransaction add(Seed seed, LocalDate date, String amount, String narration) {
        partitions.ensureRange("transaction", JAN, FEB.plusMonths(1));
        return transactions.save(new AccountTransaction(
            seed.account().getId(), seed.user().getId(), date, date, Money.inr(amount), narration, "Shop",
            null, RecordSource.AA, "k-" + UUID.randomUUID()));
    }

    @Test
    void pagesNewestFirstWithAnOpaqueCursorAndNoGapsOrRepeats() throws Exception {
        Seed seed = seedUser();
        for (int i = 0; i < 25; i++) {
            // several rows share a date, so the id tiebreak is exercised
            add(seed, JAN.plusDays(i / 5), "-10.00", "row " + i);
        }

        Set<String> seen = new HashSet<>();
        List<String> dates = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var request = get("/api/v1/transactions").param("limit", "10").with(as(seed.subject()));
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(body, "$.content[*].id");
            List<String> pageDates = JsonPath.read(body, "$.content[*].valueDate");
            ids.forEach(id -> org.assertj.core.api.Assertions.assertThat(seen.add(id)).isTrue());
            dates.addAll(pageDates);
            cursor = body.contains("\"nextCursor\"") ? JsonPath.read(body, "$.nextCursor") : null;
            pages++;
        } while (cursor != null && pages < 10);

        org.assertj.core.api.Assertions.assertThat(pages).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(seen).hasSize(25);
        org.assertj.core.api.Assertions.assertThat(dates).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void lastPageHasNoNextCursor() throws Exception {
        Seed seed = seedUser();
        add(seed, JAN, "5.00", "only one");

        mockMvc.perform(get("/api/v1/transactions").with(as(seed.subject())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.nextCursor").doesNotExist())
            .andExpect(jsonPath("$.limit", equalTo(50)))
            .andExpect(jsonPath("$.content[0].amount.amount", equalTo("5.00")))
            .andExpect(jsonPath("$.content[0].direction", equalTo("CREDIT")));
    }

    @Test
    void filtersByDateRangeAndDirection() throws Exception {
        Seed seed = seedUser();
        add(seed, JAN, "-10.00", "jan debit");
        add(seed, FEB, "-20.00", "feb debit");
        add(seed, FEB.plusDays(1), "30.00", "feb credit");

        mockMvc.perform(get("/api/v1/transactions").param("from", "2024-02-01").with(as(seed.subject())))
            .andExpect(jsonPath("$.content", hasSize(2)));
        mockMvc.perform(get("/api/v1/transactions").param("to", "2024-01-31").with(as(seed.subject())))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].narration", equalTo("jan debit")));
        mockMvc.perform(get("/api/v1/transactions").param("direction", "CREDIT").with(as(seed.subject())))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].narration", equalTo("feb credit")));
    }

    @Test
    void perAccountFeedOnlyShowsThatAccountAnd404sForSomeoneElses() throws Exception {
        Seed mine = seedUser();
        Seed theirs = seedUser();
        add(mine, JAN, "1.00", "mine");
        add(theirs, JAN, "2.00", "theirs");

        mockMvc.perform(get("/api/v1/accounts/" + mine.account().getId() + "/transactions").with(as(mine.subject())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].narration", equalTo("mine")));
        mockMvc.perform(get("/api/v1/accounts/" + theirs.account().getId() + "/transactions").with(as(mine.subject())))
            .andExpect(status().isNotFound());
        // the cross-account feed never leaks other users' rows, even when asked for their account id
        mockMvc.perform(get("/api/v1/transactions").param("accountId", theirs.account().getId().toString())
                .with(as(mine.subject())))
            .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void getsOneTransactionAndHidesOthers() throws Exception {
        Seed mine = seedUser();
        Seed theirs = seedUser();
        AccountTransaction own = add(mine, JAN, "-99.99", "coffee");
        AccountTransaction other = add(theirs, JAN, "-1.00", "private");

        mockMvc.perform(get("/api/v1/transactions/" + own.getId()).with(as(mine.subject())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.narration", equalTo("coffee")))
            .andExpect(jsonPath("$.amount.amount", equalTo("-99.99")))
            .andExpect(jsonPath("$.balanceAfter").doesNotExist());
        mockMvc.perform(get("/api/v1/transactions/" + other.getId()).with(as(mine.subject())))
            .andExpect(status().isNotFound());
    }

    @Test
    void exposesTheExpenseDerivedFromATransaction() throws Exception {
        Seed seed = seedUser();
        AccountTransaction withExpense = add(seed, JAN, "-40.00", "grocer");
        AccountTransaction without = add(seed, JAN, "-5.00", "tea");
        partitions.ensureMonth("expense", java.time.YearMonth.from(JAN));
        UUID food = UUID.fromString("40a8ed3d-45f2-50fe-8806-e94e6a8eb931");
        Expense expense = expenses.save(new Expense(seed.user().getId(), JAN, Money.inr("40.00"), food, null, "Grocer",
            Necessity.ESSENTIAL, ExpenseOrigin.AA, withExpense.getId()));

        mockMvc.perform(get("/api/v1/transactions/" + withExpense.getId()).with(as(seed.subject())))
            .andExpect(jsonPath("$.expenseId", equalTo(expense.getId().toString())));
        mockMvc.perform(get("/api/v1/transactions/" + without.getId()).with(as(seed.subject())))
            .andExpect(jsonPath("$.expenseId").doesNotExist());
        mockMvc.perform(get("/api/v1/transactions").with(as(seed.subject())))
            .andExpect(jsonPath("$.content[?(@.narration=='grocer')].expenseId")
                .value(org.hamcrest.Matchers.contains(expense.getId().toString())))
            .andExpect(jsonPath("$.content[?(@.narration=='tea')].expenseId").isEmpty());
    }

    @Test
    void rejectsBadInputWith400() throws Exception {
        Seed seed = seedUser();
        add(seed, JAN, "1.00", "x");
        add(seed, JAN, "2.00", "y");
        String body = mockMvc.perform(get("/api/v1/transactions").param("limit", "1").with(as(seed.subject())))
            .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(body, "$.nextCursor");

        mockMvc.perform(get("/api/v1/transactions").param("limit", "0").with(as(seed.subject())))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/transactions").param("limit", "201").with(as(seed.subject())))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/transactions").param("from", "yesterday").with(as(seed.subject())))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/transactions").param("from", "2024-02-01").param("to", "2024-01-01")
                .with(as(seed.subject())))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/transactions").param("cursor", "garbage").with(as(seed.subject())))
            .andExpect(status().isBadRequest());
        // a cursor is bound to the filters it was issued with
        mockMvc.perform(get("/api/v1/transactions").param("cursor", cursor).param("direction", "DEBIT")
                .with(as(seed.subject())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")).andExpect(status().isUnauthorized());
    }
}
