package com.rohit.nyvra.expense;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Re-categorises a user's existing expenses with a rule they just created. Runs asynchronously once the rule is
 * committed, in its own transaction.
 *
 * <p>Only expenses whose category the system chose ({@link CategorySource#AUTO}) are touched, so a category the
 * user picked by hand is never overwritten; split parents and split parts are skipped. Expenses carry a merchant
 * but neither a bank narration nor an MCC, so only {@link MatcherType#MERCHANT_REGEX} rules change history —
 * the other types apply to new transactions at ingestion time.
 */
@Component
public class RuleBackfill {

    private static final Logger log = LoggerFactory.getLogger(RuleBackfill.class);

    /** Candidates read per round trip. */
    private static final int BATCH_SIZE = 500;

    /** Expense persistence. */
    private final ExpenseRepository expenses;

    /** Rule persistence. */
    private final CategorisationRuleRepository rules;

    /** Category persistence. */
    private final CategoryRepository categories;

    /**
     * Creates the component.
     *
     * @param expenses   expense persistence
     * @param rules      rule persistence
     * @param categories category persistence
     */
    public RuleBackfill(ExpenseRepository expenses, CategorisationRuleRepository rules, CategoryRepository categories) {
        this.expenses = expenses;
        this.rules = rules;
        this.categories = categories;
    }

    /**
     * Event entry point: runs {@link #apply} on a background thread after the rule's transaction commits, in a
     * transaction of its own (a call from inside this class would bypass the proxy, so the transaction starts here).
     *
     * @param event the created rule
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRuleCreated(RuleCreatedEvent event) {
        try {
            apply(event.userId(), event.ruleId());
        } catch (RuntimeException e) {
            log.warn("Applying rule {} to existing expenses failed", event.ruleId(), e);
        }
    }

    /**
     * Applies one rule to the user's existing auto-categorised expenses.
     *
     * @param userId the rule's owner
     * @param ruleId the rule
     * @return how many expenses were re-categorised
     */
    @Transactional
    public int apply(UUID userId, UUID ruleId) {
        CategorisationRule rule = rules.findByIdAndUserId(ruleId, userId).orElse(null);
        if (rule == null || rule.getMatcherType() != MatcherType.MERCHANT_REGEX) {
            return 0;
        }
        Map<UUID, Category> visible = categories.findVisibleTo(userId).stream()
            .collect(Collectors.toMap(Category::getId, c -> c));
        Category target = visible.get(rule.getCategoryId());
        if (target == null) {
            return 0;
        }
        UUID categoryId = target.getParentId() == null ? target.getId() : target.getParentId();
        UUID subcategoryId = target.getParentId() == null ? null : target.getId();
        Pattern pattern = SafeRegex.compile(rule.getMatcherValue());

        int changed = 0;
        Pageable page = PageRequest.of(0, BATCH_SIZE);
        Slice<Expense> slice;
        do {
            slice = expenses.findBackfillCandidates(userId, CategorySource.AUTO, page);
            Set<UUID> splitParents = splitParentsIn(userId, slice.getContent());
            for (Expense expense : slice) {
                if (!splitParents.contains(expense.getId()) && SafeRegex.find(pattern, expense.getMerchant())) {
                    expense.autoRecategorise(categoryId, subcategoryId, rule.getNecessity());
                    changed++;
                }
            }
            page = slice.nextPageable();
        } while (slice.hasNext());
        log.info("Rule {} re-categorised {} existing expense(s)", ruleId, changed);
        return changed;
    }

    private Set<UUID> splitParentsIn(UUID userId, List<Expense> batch) {
        if (batch.isEmpty()) {
            return Set.of();
        }
        Set<UUID> parents = new HashSet<>();
        expenses.findByUserIdAndParentExpenseIdIn(userId, batch.stream().map(Expense::getId).toList())
            .forEach(child -> parents.add(child.getParentExpenseId()));
        return parents;
    }
}
