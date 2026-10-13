package com.rohit.nyvra.analytics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rohit.nyvra.accounts.AccountStatus;
import com.rohit.nyvra.accounts.AccountType;
import com.rohit.nyvra.accounts.CardDetail;
import com.rohit.nyvra.accounts.CardDetailRepository;
import com.rohit.nyvra.accounts.FinancialAccount;
import com.rohit.nyvra.accounts.FinancialAccountRepository;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.expense.Expense;
import com.rohit.nyvra.expense.ExpenseRepository;
import com.rohit.nyvra.expense.Necessity;
import com.rohit.nyvra.goals.GoalProgressSnapshot;
import com.rohit.nyvra.goals.GoalProgressSource;
import com.rohit.nyvra.income.IncomeCadence;
import com.rohit.nyvra.income.IncomeEntryRepository;
import com.rohit.nyvra.income.IncomeSource;
import com.rohit.nyvra.income.IncomeSourceRepository;
import com.rohit.nyvra.income.IncomeType;
import com.rohit.nyvra.networth.PortfolioValueSource;
import com.rohit.nyvra.portfolio.AssetClass;
import com.rohit.nyvra.portfolio.PortfolioHolding;
import com.rohit.nyvra.portfolio.PortfolioHoldingRepository;
import com.rohit.nyvra.portfolio.PortfolioValuation;
import com.rohit.nyvra.portfolio.PriceQuote;
import com.rohit.nyvra.portfolio.PriceQuoteRepository;
import com.rohit.nyvra.user.UserPreferencesReader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers one user's real data from income, expenses, accounts, portfolio and goals, and turns it into
 * {@link DriverMetrics}. It only reads, through the other modules' repositories and the
 * {@link GoalProgressSource} and {@link PortfolioValueSource} seams, and never by the signed-in user, so
 * background jobs can score anyone. The gate and ratio rules live in {@link DriverMetricsCalculator}.
 *
 * <p>Ratios use the last {@code windowMonths} full calendar months (so this month's partial data never
 * skews them); burn rate and history depth use the last {@code historyMonths}. Spending counts the same
 * rows the spending-habits view counts: split parents are replaced by their parts and rows excluded from
 * habits are skipped.
 */
@Component
public class DriverMetricsAssembler {

    /** The user's display time zone, which decides what "today" and a calendar month are. */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");

    /** Expenses. */
    private final ExpenseRepository expenses;
    /** Income entries. */
    private final IncomeEntryRepository incomeEntries;
    /** Income sources, for the variable-income test. */
    private final IncomeSourceRepository incomeSources;
    /** Accounts. */
    private final FinancialAccountRepository accounts;
    /** Card limits. */
    private final CardDetailRepository cards;
    /** Open holdings. */
    private final PortfolioHoldingRepository holdings;
    /** Latest prices. */
    private final PriceQuoteRepository quotes;
    /** Total portfolio value, shared with net worth. */
    private final PortfolioValueSource portfolioValue;
    /** Goal progress. */
    private final GoalProgressSource goals;
    /** The user's risk band and emergency-fund target. */
    private final UserPreferencesReader preferences;
    /** Window and gate parameters. */
    private final HealthScoreInputRules inputs;
    /** Rule parameters. */
    private final FinancialRules rules;

    /**
     * Creates the assembler.
     *
     * @param expenses       expense repository
     * @param incomeEntries  income entry repository
     * @param incomeSources  income source repository
     * @param accounts       account repository
     * @param cards          card detail repository
     * @param holdings       portfolio holding repository
     * @param quotes         price quote repository
     * @param portfolioValue the portfolio valuation seam
     * @param goals          the goal progress seam
     * @param preferences    the user preferences reader
     * @param inputs         window and gate parameters
     * @param rules          financial rule parameters
     */
    public DriverMetricsAssembler(ExpenseRepository expenses, IncomeEntryRepository incomeEntries,
                                  IncomeSourceRepository incomeSources, FinancialAccountRepository accounts,
                                  CardDetailRepository cards, PortfolioHoldingRepository holdings,
                                  PriceQuoteRepository quotes, PortfolioValueSource portfolioValue,
                                  GoalProgressSource goals, UserPreferencesReader preferences,
                                  HealthScoreInputRules inputs, FinancialRules rules) {
        this.expenses = expenses;
        this.incomeEntries = incomeEntries;
        this.incomeSources = incomeSources;
        this.accounts = accounts;
        this.cards = cards;
        this.holdings = holdings;
        this.quotes = quotes;
        this.portfolioValue = portfolioValue;
        this.goals = goals;
        this.preferences = preferences;
        this.inputs = inputs;
        this.rules = rules;
    }

    /**
     * Assembles a user's drivers as of today (Asia/Kolkata).
     *
     * @param userId the user
     * @return the drivers, ready for {@link HealthScoreService#record}
     */
    @Transactional(readOnly = true)
    public DriverMetrics assemble(UUID userId) {
        return assemble(userId, LocalDate.now(DISPLAY_ZONE), Instant.now());
    }

    /**
     * Assembles a user's drivers for a given day.
     *
     * @param userId the user
     * @param today  the day windows and staleness are measured from
     * @param now    the instant account staleness is measured from
     * @return the drivers
     */
    @Transactional(readOnly = true)
    public DriverMetrics assemble(UUID userId, LocalDate today, Instant now) {
        return DriverMetricsCalculator.calculate(facts(userId, today, now), inputs, rules);
    }

    /**
     * Reads and aggregates everything the calculator needs.
     *
     * @param userId the user
     * @param today  the day windows are measured from
     * @param now    the instant account staleness is measured from
     * @return the facts
     */
    DriverFacts facts(UUID userId, LocalDate today, Instant now) {
        YearMonth thisMonth = YearMonth.from(today);
        LocalDate historyStart = thisMonth.minusMonths(inputs.historyMonths()).atDay(1);
        LocalDate windowStart = thisMonth.minusMonths(inputs.windowMonths()).atDay(1);
        LocalDate end = thisMonth.minusMonths(1).atEndOfMonth();

        List<Expense> rows = expenses.findByUserIdAndDateBetween(userId, historyStart, end);
        Set<UUID> splitParents = rows.stream().map(Expense::getParentExpenseId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        List<Expense> counted = rows.stream()
            .filter(e -> !e.isExcludedFromHabits() && !splitParents.contains(e.getId())
                && e.getNecessity() != null)
            .toList();

        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal essential = BigDecimal.ZERO;
        BigDecimal discretionary = BigDecimal.ZERO;
        BigDecimal debtRepayment = BigDecimal.ZERO;
        BigDecimal burn = BigDecimal.ZERO;
        Set<YearMonth> spendMonths = new HashSet<>();
        Set<YearMonth> burnMonths = new HashSet<>();
        LocalDate earliest = null;
        for (Expense e : counted) {
            if (e.getNecessity() == Necessity.SAVINGS_TRANSFER) {
                continue;
            }
            earliest = earliest == null || e.getDate().isBefore(earliest) ? e.getDate() : earliest;
            BigDecimal amount = e.getAmount().amount();
            YearMonth month = YearMonth.from(e.getDate());
            if (e.getNecessity() == Necessity.ESSENTIAL || e.getNecessity() == Necessity.DEBT_REPAYMENT) {
                burn = burn.add(amount);
                burnMonths.add(month);
            }
            if (e.getDate().isBefore(windowStart)) {
                continue;
            }
            spendMonths.add(month);
            spend = spend.add(amount);
            switch (e.getNecessity()) {
                case ESSENTIAL -> essential = essential.add(amount);
                case DISCRETIONARY -> discretionary = discretionary.add(amount);
                case DEBT_REPAYMENT -> debtRepayment = debtRepayment.add(amount);
                default -> { }
            }
        }

        List<IncomeEntryRepository.TypeTotals> income = incomeEntries.sumByType(userId, windowStart, end);
        BigDecimal incomeNet = income.stream().map(IncomeEntryRepository.TypeTotals::getTotalNet)
            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        // incomeEntryCount is only compared with zero by the gate, so one per income type is enough.
        int incomeEntryCount = income.size();

        List<FinancialAccount> open = accounts.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId).stream()
            .filter(a -> a.getStatus() != AccountStatus.CLOSED).toList();
        boolean liquidKnown = false;
        BigDecimal liquid = BigDecimal.ZERO;
        List<FinancialAccount> cardAccounts = new ArrayList<>();
        int liabilities = 0;
        Integer maxStale = null;
        for (FinancialAccount a : open) {
            if (a.getType() == AccountType.SAVINGS || a.getType() == AccountType.CURRENT) {
                liquidKnown = true;
                liquid = liquid.add(a.getCurrentBalance().amount());
            } else if (a.getType() == AccountType.LOAN || a.getType() == AccountType.CREDIT_CARD) {
                liabilities++;
                if (a.getType() == AccountType.CREDIT_CARD) {
                    cardAccounts.add(a);
                }
            }
            if (a.getSource() != RecordSource.MANUAL && a.getBalanceAsOf() != null) {
                int days = (int) Math.max(0, Duration.between(a.getBalanceAsOf(), now).toDays());
                maxStale = maxStale == null ? days : Math.max(maxStale, days);
            }
        }
        Map<UUID, FinancialAccount> cardById = cardAccounts.stream()
            .collect(Collectors.toMap(FinancialAccount::getId, Function.identity()));
        BigDecimal cardOutstanding = cardAccounts.stream().map(a -> a.getCurrentBalance().amount().abs())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cardLimit = cardById.isEmpty() ? BigDecimal.ZERO
            : cards.findByFinancialAccountIdIn(cardById.keySet()).stream().map(CardDetail::getCreditLimit)
                .filter(Objects::nonNull).map(Money::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<IncomeSource> sources = incomeSources.findByUserIdAndActiveTrueOrderByNameAsc(userId);
        boolean variable = sources.stream().anyMatch(s -> s.getCadence() == IncomeCadence.IRREGULAR)
            || sources.stream().filter(s -> s.getType() == IncomeType.BUSINESS).count() > 1;

        List<PortfolioHolding> open2 = holdings.findByUserIdAndClosedAtIsNull(userId);
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal unclassified = BigDecimal.ZERO;
        List<BigDecimal> stocks = new ArrayList<>();
        Map<UUID, PriceQuote> latest = open2.isEmpty() ? Map.of() : quotes
            .findLatestByInstrumentIds(open2.stream().map(PortfolioHolding::getInstrumentId).distinct().toList())
            .stream().collect(Collectors.toMap(PriceQuote::getInstrumentId, Function.identity(), (a, b) -> a));
        for (PortfolioHolding h : open2) {
            boolean directEquity = h.getAssetClass() == AssetClass.EQUITY
                || h.getAssetClass() == AssetClass.FOREIGN_EQUITY;
            // Funds and NPS mix equity and debt in unknown proportions, so they can only be set aside.
            boolean unknownMix = h.getAssetClass() == AssetClass.MUTUAL_FUND || h.getAssetClass() == AssetClass.NPS;
            if (!Money.INR.equals(h.getCurrency()) || (!directEquity && !unknownMix)) {
                continue;
            }
            PriceQuote quote = latest.get(h.getInstrumentId());
            Money value = quote == null ? null
                : PortfolioValuation.current(h.getQuantity(), quote.getPrice(), h.getCurrency());
            if (value == null) {
                value = PortfolioValuation.invested(h.getQuantity(), h.getAvgCost(), h.getCurrency());
            }
            if (value != null && directEquity) {
                equity = equity.add(value.amount());
                stocks.add(value.amount());
            } else if (value != null) {
                unclassified = unclassified.add(value.amount());
            }
        }

        List<DriverFacts.GoalRatio> goalRatios = new ArrayList<>();
        for (GoalProgressSnapshot g : goals.activeGoalProgress(userId, today)) {
            goalRatios.add(new DriverFacts.GoalRatio(g.progressRatio(), g.weight()));
        }

        UserPreferencesReader.ScorePreferences prefs = preferences.scorePreferences(userId);
        Integer ageYears = prefs.dateOfBirth() == null ? null : Period.between(prefs.dateOfBirth(), today).getYears();
        int historyDays = earliest == null ? 0 : (int) ChronoUnit.DAYS.between(earliest, today);
        return new DriverFacts(incomeNet, incomeEntryCount, spendMonths.size(), spend, essential, discretionary,
            debtRepayment, burnMonths.size(), burn, liquidKnown, liquid, variable, !open.isEmpty(), liabilities,
            cardOutstanding, cardLimit, portfolioValue.currentValue(userId).amount(), open2.size(), equity, stocks,
            goalRatios, maxStale, historyDays, unclassified, prefs.riskBand(),
            prefs.emergencyFundMonthsTarget(), ageYears);
    }
}
