package com.rohit.nyvra.expense;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.partition.MonthlyPartitions;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of {@code GET /api/v1/spending/habits}: totals and shares, what is left out of spending
 * (savings transfers, excluded expenses, split parents), per-top-level-category roll-up, and month handling.
 */
@AutoConfigureMockMvc
class SpendingControllerIntegrationTest extends AbstractIntegrationTest {

    /** Seeded in V4.1: "Food & dining", top level. */
    private static final UUID FOOD = UUID.fromString("40a8ed3d-45f2-50fe-8806-e94e6a8eb931");

    /** Seeded in V4.1: "Groceries", a child of {@link #FOOD}. */
    private static final UUID GROCERIES = UUID.fromString("566d0670-1558-5400-b5d1-5889e0dd8af9");

    /** Seeded in V4.1: "Shopping", top level. */
    private static final UUID SHOPPING = UUID.fromString("777df330-9c34-5d70-856b-cc533d7b77f6");

    /** Seeded in V4.1: "EMIs & loans", top level. */
    private static final UUID EMI = UUID.fromString("ff308a7b-f59e-557f-88ca-ad2ad67ae1ca");

    /** Seeded in V4.1: "Savings & investments", top level. */
    private static final UUID SAVINGS = UUID.fromString("49b7aad8-7477-544d-9cdf-46b4faf1a3b5");

    /** The month all fixtures live in. */
    private static final LocalDate SEPT = LocalDate.of(2026, 9, 10);

    /** Issues requests against the full application. */
    @Autowired
    private MockMvc mockMvc;

    /** Creates the users the tests act as. */
    @Autowired
    private UserProfileRepository users;

    /** Seeds expenses. */
    @Autowired
    private ExpenseRepository expenses;

    /** Makes sure the expense partitions exist for the seeded dates. */
    @Autowired
    private MonthlyPartitions partitions;

    /**
     * Persists a fresh user.
     *
     * @return the user
     */
    private UserProfile seedUser() {
        return users.save(UserProfileMother.aUserProfile());
    }

    /**
     * Authenticates a request as the user.
     *
     * @param user the user
     * @return a post-processor adding the JWT
     */
    private static RequestPostProcessor as(UserProfile user) {
        return jwt().jwt(jwt -> jwt.subject(user.getKeycloakSubject()));
    }

    /**
     * Saves a manual expense on {@link #SEPT}.
     *
     * @param user      the owner
     * @param amount    the amount
     * @param category  the category
     * @param sub       the subcategory, or null
     * @param necessity the necessity
     * @return the saved expense
     */
    private Expense spend(UserProfile user, String amount, UUID category, UUID sub, Necessity necessity) {
        partitions.ensureMonth("expense", YearMonth.from(SEPT));
        return expenses.save(new Expense(user.getId(), SEPT, Money.inr(amount), category, sub, null, necessity,
            ExpenseOrigin.MANUAL, null));
    }

    /**
     * A month with spending in several necessities: totals, shares and the top-level roll-up are right, savings
     * transfers and excluded expenses stay out of the total, and categories are sorted by amount.
     *
     * @throws Exception on request failure
     */
    @Test
    void summarisesAMonth() throws Exception {
        UserProfile user = seedUser();
        spend(user, "600.00", FOOD, GROCERIES, Necessity.ESSENTIAL);   // rolls up to Food & dining
        spend(user, "200.00", FOOD, null, Necessity.ESSENTIAL);
        spend(user, "250.00", SHOPPING, null, Necessity.DISCRETIONARY);
        spend(user, "150.00", EMI, null, Necessity.DEBT_REPAYMENT);
        spend(user, "5000.00", SAVINGS, null, Necessity.SAVINGS_TRANSFER);
        Expense excluded = spend(user, "9999.00", SHOPPING, null, Necessity.DISCRETIONARY);
        excluded.setExcludedFromHabits(true);
        expenses.save(excluded);

        mockMvc.perform(get("/api/v1/spending/habits").param("month", "2026-09").with(as(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.month", equalTo("2026-09")))
            .andExpect(jsonPath("$.totalSpend.amount", equalTo("1200.00")))
            .andExpect(jsonPath("$.totalSpend.currency", equalTo("INR")))
            .andExpect(jsonPath("$.essentialPct", closeTo(66.7, 0.001)))
            .andExpect(jsonPath("$.discretionaryPct", closeTo(20.8, 0.001)))
            .andExpect(jsonPath("$.debtRepaymentPct", closeTo(12.5, 0.001)))
            .andExpect(jsonPath("$.savingsTransfers.amount", equalTo("5000.00")))
            .andExpect(jsonPath("$.byCategory", hasSize(3)))
            .andExpect(jsonPath("$.byCategory[0].category.name", equalTo("Food & dining")))
            .andExpect(jsonPath("$.byCategory[0].amount.amount", equalTo("800.00")))
            .andExpect(jsonPath("$.byCategory[0].pct", closeTo(66.7, 0.001)))
            .andExpect(jsonPath("$.byCategory[1].category.name", equalTo("Shopping")))
            .andExpect(jsonPath("$.byCategory[2].category.name", equalTo("EMIs & loans")))
            .andExpect(jsonPath("$.computedAt").exists());
    }

    /**
     * A split expense counts once, through its parts: the parent is left out and each part lands in its own category.
     *
     * @throws Exception on request failure
     */
    @Test
    void countsASplitThroughItsPartsNotItsParent() throws Exception {
        UserProfile user = seedUser();
        Expense parent = spend(user, "1000.00", SHOPPING, null, Necessity.DISCRETIONARY);
        expenses.save(Expense.splitOf(parent, Money.inr("700.00"), FOOD, GROCERIES, Necessity.ESSENTIAL, null));
        expenses.save(Expense.splitOf(parent, Money.inr("300.00"), SHOPPING, null, Necessity.DISCRETIONARY, null));

        mockMvc.perform(get("/api/v1/spending/habits").param("month", "2026-09").with(as(user)))
            .andExpect(jsonPath("$.totalSpend.amount", equalTo("1000.00")))
            .andExpect(jsonPath("$.essentialPct", closeTo(70.0, 0.001)))
            .andExpect(jsonPath("$.discretionaryPct", closeTo(30.0, 0.001)))
            .andExpect(jsonPath("$.byCategory[0].category.name", equalTo("Food & dining")))
            .andExpect(jsonPath("$.byCategory[0].amount.amount", equalTo("700.00")))
            .andExpect(jsonPath("$.byCategory[1].amount.amount", equalTo("300.00")));
    }

    /**
     * A month with no expenses yields zero totals and an empty breakdown, not an error.
     *
     * @throws Exception on request failure
     */
    @Test
    void anEmptyMonthHasZeroTotals() throws Exception {
        UserProfile user = seedUser();

        mockMvc.perform(get("/api/v1/spending/habits").param("month", "2026-01").with(as(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalSpend.amount", equalTo("0.00")))
            .andExpect(jsonPath("$.essentialPct", closeTo(0.0, 0.001)))
            .andExpect(jsonPath("$.savingsTransfers.amount", equalTo("0.00")))
            .andExpect(jsonPath("$.byCategory", empty()));
    }

    /**
     * Only the caller's expenses are counted, and only those in the requested month.
     *
     * @throws Exception on request failure
     */
    @Test
    void ignoresOtherUsersAndOtherMonths() throws Exception {
        UserProfile user = seedUser();
        UserProfile other = seedUser();
        spend(user, "100.00", FOOD, null, Necessity.ESSENTIAL);
        spend(other, "999.00", FOOD, null, Necessity.ESSENTIAL);
        partitions.ensureMonth("expense", YearMonth.of(2026, 8));
        expenses.save(new Expense(user.getId(), LocalDate.of(2026, 8, 31), Money.inr("777.00"), FOOD, null, null,
            Necessity.ESSENTIAL, ExpenseOrigin.MANUAL, null));

        mockMvc.perform(get("/api/v1/spending/habits").param("month", "2026-09").with(as(user)))
            .andExpect(jsonPath("$.totalSpend.amount", equalTo("100.00")));
    }

    /**
     * Without a month it reports the current one, and a malformed month is a 400.
     *
     * @throws Exception on request failure
     */
    @Test
    void defaultsToTheCurrentMonthAndRejectsABadOne() throws Exception {
        UserProfile user = seedUser();

        mockMvc.perform(get("/api/v1/spending/habits").with(as(user))).andExpect(status().isOk())
            .andExpect(jsonPath("$.month", matchesPattern("\\d{4}-(0[1-9]|1[0-2])")))
            .andExpect(jsonPath("$.month", equalTo(YearMonth.now(SpendingService.DISPLAY_ZONE).toString())));
        mockMvc.perform(get("/api/v1/spending/habits").param("month", "2026-13").with(as(user)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/spending/habits").param("month", "September").with(as(user)))
            .andExpect(status().isBadRequest());
    }

    /**
     * Without a token the endpoint answers 401.
     *
     * @throws Exception on request failure
     */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/spending/habits")).andExpect(status().isUnauthorized());
    }
}
