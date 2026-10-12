package com.rohit.nyvra.expense;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.expense.dto.CategoryRef;
import com.rohit.nyvra.expense.dto.SpendingHabitsResponse;
import com.rohit.nyvra.expense.dto.SpendingHabitsResponse.CategorySpend;
import com.rohit.nyvra.user.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the monthly spending breakdown for the signed-in user (FINANCIAL_RULES §1.1: spending leaves out
 * {@code SAVINGS_TRANSFER} and excluded-from-habits expenses). All percentages are computed here, never in the
 * client.
 *
 * <p>Computed on demand from the month's expenses, so it is always current; a split parent is replaced by its
 * parts so nothing is counted twice. Each expense is attributed to its top-level category.
 */
@Service
public class SpendingService {

    /** The user's display time zone, which decides what "the current month" is. */
    static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");

    /** Spending is reported in INR only (v1). */
    private static final String CURRENCY = Money.INR;

    /** Scale of the percentages on the wire. */
    private static final int PCT_SCALE = 1;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Expense persistence. */
    private final ExpenseRepository expenses;

    /** Category persistence. */
    private final CategoryRepository categories;

    /** Resolves the caller. */
    private final CurrentUserService currentUser;

    /**
     * Creates the service.
     *
     * @param expenses    expense persistence
     * @param categories  category persistence
     * @param currentUser resolves the caller
     */
    public SpendingService(ExpenseRepository expenses, CategoryRepository categories, CurrentUserService currentUser) {
        this.expenses = expenses;
        this.categories = categories;
        this.currentUser = currentUser;
    }

    /**
     * Computes the breakdown for one month.
     *
     * @param month the month, or null for the current month in Asia/Kolkata
     * @return the breakdown; a month without expenses yields zero totals and an empty {@code byCategory}
     */
    @Transactional(readOnly = true)
    public SpendingHabitsResponse habits(YearMonth month) {
        UUID userId = currentUser.currentUser().getId();
        YearMonth period = month != null ? month : YearMonth.now(DISPLAY_ZONE);

        List<Expense> rows = expenses.findByUserIdAndDateBetween(userId, period.atDay(1), period.atEndOfMonth());
        Set<UUID> splitParents = rows.stream().map(Expense::getParentExpenseId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        List<Expense> counted = rows.stream()
            .filter(e -> !e.isExcludedFromHabits() && !splitParents.contains(e.getId()))
            .toList();

        Money savings = Money.zero(CURRENCY);
        Money total = Money.zero(CURRENCY);
        Map<Necessity, Money> byNecessity = new EnumMap<>(Necessity.class);
        Map<UUID, Money> byRoot = new HashMap<>();
        Map<UUID, Category> visible = categories.findVisibleTo(userId).stream()
            .collect(Collectors.toMap(Category::getId, c -> c));

        for (Expense expense : counted) {
            Money amount = expense.getAmount();
            if (expense.getNecessity() == Necessity.SAVINGS_TRANSFER) {
                savings = savings.plus(amount);
                continue;
            }
            total = total.plus(amount);
            byNecessity.merge(expense.getNecessity(), amount, Money::plus);
            byRoot.merge(rootOf(visible, expense.getCategoryId()), amount, Money::plus);
        }

        Money spend = total;
        List<CategorySpend> byCategory = byRoot.entrySet().stream()
            .sorted(Map.Entry.<UUID, Money>comparingByValue(Comparator.comparing(Money::amount)).reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(e -> new CategorySpend(CategoryRef.from(visible.get(e.getKey())), MoneyDto.from(e.getValue()),
                pct(e.getValue(), spend)))
            .toList();

        return new SpendingHabitsResponse(period.toString(), MoneyDto.from(total),
            pct(byNecessity.get(Necessity.ESSENTIAL), total),
            pct(byNecessity.get(Necessity.DISCRETIONARY), total),
            pct(byNecessity.get(Necessity.DEBT_REPAYMENT), total),
            MoneyDto.from(savings), byCategory, Instant.now());
    }

    /** Walks up to the top-level category; an id that is not visible stays itself. */
    private static UUID rootOf(Map<UUID, Category> visible, UUID categoryId) {
        UUID current = categoryId;
        Category category = visible.get(current);
        while (category != null && category.getParentId() != null) {
            current = category.getParentId();
            category = visible.get(current);
        }
        return current;
    }

    /** {@code part / total} in percent points to one decimal; zero when there is no spend. */
    private static BigDecimal pct(Money part, Money total) {
        if (part == null || total.amount().signum() == 0) {
            return BigDecimal.ZERO.setScale(PCT_SCALE);
        }
        return part.amount().multiply(HUNDRED).divide(total.amount(), PCT_SCALE, RoundingMode.HALF_UP);
    }
}
