package com.rohit.nyvra.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;

class ExpenseRepositoryIntegrationTest extends AbstractIntegrationTest {

    /** "Food & dining" and its "Groceries" child — fixed ids from V4.1__seed_categories.sql. */
    private static final UUID FOOD = UUID.fromString("40a8ed3d-45f2-50fe-8806-e94e6a8eb931");

    @Autowired
    private UserProfileRepository users;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private CategorisationRuleRepository rules;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private SpendingHabitSnapshotRepository snapshots;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seedsTheSystemCategoryTree() {
        Category food = categories.findById(FOOD).orElseThrow();

        assertThat(food.isSystem()).isTrue();
        assertThat(food.getName()).isEqualTo("Food & dining");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM category WHERE parent_id = ? AND system", Integer.class, FOOD)).isEqualTo(3);
    }

    @Test
    void showsEachUserOnlyTheSystemTreeAndTheirOwnCustomCategories() {
        UserProfile alice = users.save(UserProfileMother.aUserProfile());
        UserProfile bob = users.save(UserProfileMother.aUserProfile());
        categories.save(Category.custom(alice.getId(), FOOD, "Office lunches", Necessity.DISCRETIONARY));

        assertThat(categories.findVisibleTo(alice.getId())).extracting(Category::getName).contains("Office lunches");
        assertThat(categories.findVisibleTo(bob.getId())).extracting(Category::getName)
            .doesNotContain("Office lunches")
            .contains("Food & dining", "Groceries");
    }

    @Test
    void rejectsADuplicateCustomCategoryUnderTheSameParent() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        categories.save(Category.custom(user.getId(), FOOD, "Snacks", Necessity.DISCRETIONARY));

        assertThatThrownBy(() -> categories.saveAndFlush(
            Category.custom(user.getId(), FOOD, "Snacks", Necessity.DISCRETIONARY)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ordersUserRulesBeforeSystemRules() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        CategorisationRule system = rules.save(
            new CategorisationRule(null, MatcherType.MERCHANT_REGEX, "(?i)zomato-" + user.getId(), FOOD,
                Necessity.DISCRETIONARY, 1000));
        CategorisationRule mine = rules.save(
            new CategorisationRule(user.getId(), MatcherType.MERCHANT_REGEX, "(?i)zomato", FOOD,
                Necessity.ESSENTIAL, 1));

        assertThat(rules.findApplicableTo(user.getId()))
            .extracting(CategorisationRule::getId)
            .containsSubsequence(mine.getId(), system.getId());
    }

    @Test
    void storesSplitsOnTheirParentsDateAndPagesByMonth() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        LocalDate today = LocalDate.now();
        Expense parent = expenses.save(new Expense(user.getId(), today, Money.inr("1500.00"), FOOD, null,
            "BigBasket", Necessity.ESSENTIAL, ExpenseOrigin.MANUAL, null));
        expenses.save(Expense.splitOf(parent, Money.inr("1000.00"), FOOD, null, Necessity.ESSENTIAL));
        expenses.save(Expense.splitOf(parent, Money.inr("500.00"), FOOD, null, Necessity.DISCRETIONARY));

        assertThat(expenses.findByParentExpenseIdAndDate(parent.getId(), today))
            .hasSize(2)
            .allSatisfy(child -> assertThat(child.getOrigin()).isEqualTo(ExpenseOrigin.SPLIT));
        assertThat(expenses.findByUserIdAndDateBetweenOrderByDateDescIdDesc(
            user.getId(), today.withDayOfMonth(1), today, PageRequest.of(0, 10)).getTotalElements())
            .isEqualTo(3);
    }

    @Test
    void rejectsASplitWhoseParentIsOnAnotherDate() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        LocalDate today = LocalDate.now();
        Expense parent = expenses.save(new Expense(user.getId(), today, Money.inr("10.00"), FOOD, null,
            null, Necessity.ESSENTIAL, ExpenseOrigin.MANUAL, null));

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO expense (id, date, user_id, parent_expense_id, amount, category_id, necessity, origin)
            VALUES (?, ?, ?, ?, 5.00, ?, 'ESSENTIAL', 'SPLIT')""",
            UUID.randomUUID(), today.minusDays(1), user.getId(), parent.getId(), FOOD))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void roundTripsAMonthlySpendingSnapshot() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        SpendingHabitSnapshot snapshot = new SpendingHabitSnapshot(user.getId(), YearMonth.of(2025, 6), "INR");
        snapshot.recompute(Map.of(FOOD, new BigDecimal("42.50")), new BigDecimal("70.00"),
            new BigDecimal("30.00"), Money.inr("54000.00"), Instant.now());
        snapshots.save(snapshot);

        SpendingHabitSnapshot reloaded =
            snapshots.findByUserIdAndPeriodMonth(user.getId(), LocalDate.of(2025, 6, 1)).orElseThrow();

        assertThat(reloaded.getByCategoryPct()).containsEntry(FOOD, new BigDecimal("42.50"));
        assertThat(reloaded.getTotalSpend()).isEqualTo(Money.inr("54000.00"));
    }
}
