package com.rohit.nyvra.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.partition.MonthlyPartitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The expense module's entry point for spends that arrive from outside the API (today: debits ingested from an
 * Account Aggregator). Each debit becomes one {@link ExpenseOrigin#AA} expense, categorised by the user's
 * rules first and the system rules after, falling back to the system {@code Other} category.
 *
 * <p>Idempotent on the Accounts transaction id: a debit that already has an expense is skipped, so a repeated
 * ingestion never double-counts a spend. The bank narration is matched against {@link MatcherType#NARRATION_REGEX}
 * and {@link MatcherType#MERCHANT_REGEX} rules; no merchant is stored because the narration is personal data the
 * ledger keeps encrypted, and no MCC is available from a deposit statement, so {@link MatcherType#MCC} rules do
 * not apply here.
 */
@Service
public class IngestedExpenseRecorder {

    /** Logger. */
    private static final Logger log = LoggerFactory.getLogger(IngestedExpenseRecorder.class);

    /** Name of the system category used when no rule matches. */
    static final String FALLBACK_CATEGORY = "Other";

    /** Necessity used when neither the rule nor the category states one. */
    private static final Necessity DEFAULT_NECESSITY = Necessity.DISCRETIONARY;

    /** Expense persistence. */
    private final ExpenseRepository expenses;

    /** Rule persistence. */
    private final CategorisationRuleRepository rules;

    /** Category persistence. */
    private final CategoryRepository categories;

    /** Creates the monthly partitions an insert needs. */
    private final MonthlyPartitions partitions;

    /**
     * Creates the recorder.
     *
     * @param expenses   expense persistence
     * @param rules      rule persistence
     * @param categories category persistence
     * @param partitions monthly partition creator
     */
    public IngestedExpenseRecorder(ExpenseRepository expenses, CategorisationRuleRepository rules,
                                   CategoryRepository categories, MonthlyPartitions partitions) {
        this.expenses = expenses;
        this.rules = rules;
        this.categories = categories;
        this.partitions = partitions;
    }

    /**
     * One debit to turn into an expense.
     *
     * @param transactionId the Accounts ledger line the spend comes from
     * @param date          accounting date
     * @param amount        positive amount, scale 2
     * @param narration     the bank's description, matched against rules and never stored on the expense
     */
    public record Debit(UUID transactionId, LocalDate date, BigDecimal amount, String narration) {
    }

    /**
     * Records an expense for each debit that has none yet.
     *
     * @param userId the owner
     * @param debits the debits, in any order
     * @return how many expenses were created
     */
    @Transactional
    public int record(UUID userId, List<Debit> debits) {
        if (debits.isEmpty()) {
            return 0;
        }
        Set<UUID> alreadyRecorded = new HashSet<>();
        expenses.findByUserIdAndTransactionIdIn(userId, debits.stream().map(Debit::transactionId).toList())
            .forEach(e -> alreadyRecorded.add(e.getTransactionId()));
        Map<UUID, Category> visible = categories.findVisibleTo(userId).stream()
            .collect(Collectors.toMap(Category::getId, c -> c));
        List<CompiledRule> compiled = compileRules(userId, visible);
        Category fallback = visible.values().stream()
            .filter(c -> c.isSystem() && c.getParentId() == null && FALLBACK_CATEGORY.equals(c.getName()))
            .findFirst().orElse(null);
        if (fallback == null) {
            log.warn("System category '{}' is missing; uncategorised debits are skipped", FALLBACK_CATEGORY);
        }

        int created = 0;
        for (Debit debit : debits) {
            if (alreadyRecorded.contains(debit.transactionId())) {
                continue;
            }
            Optional<CompiledRule> match = compiled.stream()
                .filter(r -> SafeRegex.find(r.pattern(), debit.narration() == null ? "" : debit.narration()))
                .findFirst();
            Category target = match.map(CompiledRule::category).orElse(fallback);
            if (target == null) {
                continue;
            }
            UUID categoryId = target.getParentId() == null ? target.getId() : target.getParentId();
            UUID subcategoryId = target.getParentId() == null ? null : target.getId();
            Necessity necessity = match.map(r -> r.rule().getNecessity()).filter(n -> n != null)
                .orElse(target.getNecessityDefault() == null ? DEFAULT_NECESSITY : target.getNecessityDefault());
            partitions.ensureMonth("expense", YearMonth.from(debit.date()));
            expenses.save(new Expense(userId, debit.date(), Money.of(debit.amount(), Money.INR), categoryId,
                subcategoryId, null, necessity, ExpenseOrigin.AA, debit.transactionId()));
            created++;
        }
        return created;
    }

    /**
     * Compiles the user's applicable text rules in evaluation order, dropping any whose pattern no longer compiles
     * or whose category the user cannot see.
     *
     * @param userId  the owner
     * @param visible the categories visible to the user, by id
     * @return the usable rules
     */
    private List<CompiledRule> compileRules(UUID userId, Map<UUID, Category> visible) {
        return rules.findApplicableTo(userId).stream()
            .filter(r -> r.getMatcherType() == MatcherType.NARRATION_REGEX
                || r.getMatcherType() == MatcherType.MERCHANT_REGEX)
            .filter(r -> visible.containsKey(r.getCategoryId()))
            .flatMap(r -> {
                try {
                    return Stream.of(
                        new CompiledRule(r, SafeRegex.compile(r.getMatcherValue()), visible.get(r.getCategoryId())));
                } catch (BadRequestException e) {
                    log.warn("Skipping categorisation rule {}: invalid pattern", r.getId());
                    return Stream.empty();
                }
            })
            .toList();
    }

    /**
     * A rule with its pattern compiled once per batch.
     *
     * @param rule     the rule
     * @param pattern  the compiled matcher
     * @param category the category the rule points at
     */
    private record CompiledRule(CategorisationRule rule, Pattern pattern, Category category) {
    }
}
