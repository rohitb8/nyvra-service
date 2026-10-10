package com.rohit.nyvra.expense;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.rohit.nyvra.common.api.CursorPage;
import com.rohit.nyvra.common.api.DateIdCursor;
import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.exception.ConflictException;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.common.exception.UnprocessableEntityException;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.common.partition.MonthlyPartitions;
import com.rohit.nyvra.expense.dto.CategoryRef;
import com.rohit.nyvra.expense.dto.CreateExpenseRequest;
import com.rohit.nyvra.expense.dto.ExpenseResponse;
import com.rohit.nyvra.expense.dto.ExpenseSplitResponse;
import com.rohit.nyvra.expense.dto.SplitExpenseRequest;
import com.rohit.nyvra.expense.dto.UpdateExpenseRequest;
import com.rohit.nyvra.user.CurrentUserService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expense use cases for the signed-in user. Every lookup is scoped to the caller, so another user's expense is
 * indistinguishable from a missing one (404, never 403). The feed is newest first ({@code date DESC, id DESC})
 * with keyset pagination.
 *
 * <p>A split leaves the parent in place and adds {@code SPLIT}-origin children on the parent's date whose amounts
 * sum exactly to it; the feed returns the parent with its {@code splits}, never the children on their own.
 */
@Service
public class ExpenseService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("date"), Sort.Order.desc("id"));

    /** The only currency supported in v1 (API_DESIGN §3). */
    private static final String SUPPORTED_CURRENCY = Money.INR;

    private static final String SOURCE_READ_ONLY = "SOURCE_READ_ONLY";
    private static final String ALREADY_SPLIT = "ALREADY_SPLIT";
    private static final String SPLIT_SUM_MISMATCH = "SPLIT_SUM_MISMATCH";
    private static final String INVALID_CATEGORY = "INVALID_CATEGORY";

    private final ExpenseRepository expenses;
    private final CategoryRepository categories;
    private final MonthlyPartitions partitions;
    private final CurrentUserService currentUser;

    public ExpenseService(ExpenseRepository expenses, CategoryRepository categories, MonthlyPartitions partitions,
                          CurrentUserService currentUser) {
        this.expenses = expenses;
        this.categories = categories;
        this.partitions = partitions;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public CursorPage<ExpenseResponse> list(YearMonth month, LocalDate from, LocalDate to, UUID categoryId,
                                            Collection<Necessity> necessities, ExpenseOrigin origin, String q,
                                            String cursor, int limit) {
        UUID userId = currentUser.currentUser().getId();
        if (month != null && (from != null || to != null)) {
            throw new BadRequestException("Pass either 'month' or 'from'/'to', not both");
        }
        if (month != null) {
            from = month.atDay(1);
            to = month.atEndOfMonth();
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("'from' must not be after 'to'");
        }
        LocalDate fromDate = from;
        LocalDate toDate = to;
        String search = q == null || q.isBlank() ? null : q.strip();
        if (search != null && search.length() > 100) {
            throw new BadRequestException("'q' must be at most 100 characters");
        }
        String fingerprint = "f=%s;t=%s;c=%s;n=%s;o=%s;q=%s".formatted(from, to, categoryId,
            necessities == null ? "" : necessities.stream().map(Enum::name).sorted().toList(), origin, search);
        DateIdCursor after = cursor == null || cursor.isBlank() ? null : DateIdCursor.decode(cursor, fingerprint);

        Specification<Expense> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("userId"), userId));
            // split children are only shown nested under their parent
            where.add(cb.isNull(root.get("parentExpenseId")));
            if (fromDate != null) {
                where.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("date"), fromDate));
            }
            if (toDate != null) {
                where.add(cb.lessThanOrEqualTo(root.<LocalDate>get("date"), toDate));
            }
            if (categoryId != null) {
                where.add(cb.or(cb.equal(root.get("categoryId"), categoryId),
                    cb.equal(root.get("subcategoryId"), categoryId)));
            }
            if (necessities != null && !necessities.isEmpty()) {
                where.add(root.get("necessity").in(necessities));
            }
            if (origin != null) {
                where.add(cb.equal(root.get("origin"), origin));
            }
            if (search != null) {
                String escaped = search.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                where.add(cb.like(cb.lower(root.get("merchant")), "%" + escaped + "%", '\\'));
            }
            if (after != null) {
                where.add(cb.or(
                    cb.lessThan(root.<LocalDate>get("date"), after.date()),
                    cb.and(
                        cb.equal(root.get("date"), after.date()),
                        cb.lessThan(root.<UUID>get("id"), after.id()))));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };

        // Fetch one extra row to learn whether another page exists, without a count query.
        List<Expense> rows = expenses.findBy(spec, q2 -> q2.sortBy(NEWEST_FIRST).limit(limit + 1).all());
        boolean hasMore = rows.size() > limit;
        List<Expense> page = hasMore ? rows.subList(0, limit) : rows;
        String next = null;
        if (hasMore) {
            Expense last = page.get(page.size() - 1);
            next = new DateIdCursor(last.getDate(), last.getId()).encode(fingerprint);
        }
        return new CursorPage<>(toResponses(userId, page), next, limit);
    }

    @Transactional(readOnly = true)
    public ExpenseResponse get(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        return toResponses(userId, List.of(find(userId, id))).get(0);
    }

    @Transactional
    public ExpenseResponse create(CreateExpenseRequest request) {
        UUID userId = currentUser.currentUser().getId();
        Money amount = positive(request.amount());
        Map<UUID, Category> visible = visibleCategories(userId);
        Necessity necessity = resolveNecessity(request.necessity(), visible, request.categoryId(), request.subcategoryId());

        partitions.ensureMonth("expense", YearMonth.from(request.date()));
        Expense expense = new Expense(userId, request.date(), amount, request.categoryId(), request.subcategoryId(),
            blankToNull(request.merchant()), necessity, ExpenseOrigin.MANUAL, null);
        expense.setExcludedFromHabits(Boolean.TRUE.equals(request.excludedFromHabits()));
        expenses.save(expense);
        return toResponses(userId, List.of(expense)).get(0);
    }

    @Transactional
    public ExpenseResponse update(UUID id, UpdateExpenseRequest request) {
        UUID userId = currentUser.currentUser().getId();
        Expense expense = find(userId, id);
        boolean split = hasSplits(userId, expense);

        if (request.amount() != null || request.date() != null) {
            if (expense.getOrigin() != ExpenseOrigin.MANUAL) {
                throw new ConflictException(SOURCE_READ_ONLY, "Amount and date of this expense are read-only");
            }
            if (split) {
                throw new ConflictException(ALREADY_SPLIT, "Undo the split before changing the amount or date");
            }
        }

        if (request.categoryId() != null || request.subcategoryProvided() || request.necessity() != null) {
            Map<UUID, Category> visible = visibleCategories(userId);
            boolean categoryChanged = request.categoryId() != null && !request.categoryId().equals(expense.getCategoryId());
            UUID categoryId = request.categoryId() != null ? request.categoryId() : expense.getCategoryId();
            UUID subcategoryId = request.subcategoryProvided()
                ? request.subcategoryId()
                : (categoryChanged ? null : expense.getSubcategoryId());
            requireCategories(visible, categoryId, subcategoryId);
            expense.recategorise(categoryId, subcategoryId,
                request.necessity() != null ? request.necessity() : expense.getNecessity());
        }
        if (request.merchant() != null) {
            expense.setMerchant(blankToNull(request.merchant()));
        }
        if (request.amount() != null) {
            expense.reprice(positive(request.amount()));
        }
        if (request.date() != null && !request.date().equals(expense.getDate())) {
            partitions.ensureMonth("expense", YearMonth.from(request.date()));
            expense.redate(request.date());
        }
        if (request.excludedFromHabits() != null) {
            expense.setExcludedFromHabits(request.excludedFromHabits());
            if (split) {
                childrenOf(userId, expense).forEach(c -> c.setExcludedFromHabits(request.excludedFromHabits()));
            }
        }
        expenses.saveAndFlush(expense);
        return toResponses(userId, List.of(expense)).get(0);
    }

    @Transactional
    public void delete(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        Expense expense = find(userId, id);
        if (expense.getOrigin() != ExpenseOrigin.MANUAL) {
            throw new ConflictException(SOURCE_READ_ONLY,
                "Only manual expenses can be deleted — exclude others from habits instead");
        }
        expenses.deleteAll(childrenOf(userId, expense));
        expenses.delete(expense);
    }

    @Transactional
    public ExpenseResponse split(UUID id, SplitExpenseRequest request) {
        UUID userId = currentUser.currentUser().getId();
        Expense parent = find(userId, id);
        if (parent.getParentExpenseId() != null || hasSplits(userId, parent)) {
            throw new ConflictException(ALREADY_SPLIT, "This expense is already split — undo the split first");
        }

        Money total = Money.zero(parent.getAmount().currency());
        List<Money> amounts = new ArrayList<>();
        for (SplitExpenseRequest.Part part : request.parts()) {
            Money amount = positive(part.amount());
            if (!amount.currency().equals(total.currency())) {
                throw new UnprocessableEntityException("UNSUPPORTED_CURRENCY",
                    "Parts must be in the expense's currency (" + total.currency() + ")");
            }
            amounts.add(amount);
            total = total.plus(amount);
        }
        if (total.amount().compareTo(parent.getAmount().amount()) != 0) {
            throw new UnprocessableEntityException(SPLIT_SUM_MISMATCH,
                "Parts add up to %s but the expense is %s".formatted(total.amount(), parent.getAmount().amount()));
        }

        Map<UUID, Category> visible = visibleCategories(userId);
        List<Expense> children = new ArrayList<>();
        for (int i = 0; i < request.parts().size(); i++) {
            SplitExpenseRequest.Part part = request.parts().get(i);
            Necessity necessity = resolveNecessity(part.necessity(), visible, part.categoryId(), part.subcategoryId());
            Expense child = Expense.splitOf(parent, amounts.get(i), part.categoryId(), part.subcategoryId(),
                necessity, blankToNull(part.note()));
            child.setExcludedFromHabits(parent.isExcludedFromHabits());
            children.add(child);
        }
        expenses.saveAll(children);
        return toResponses(userId, List.of(parent)).get(0);
    }

    /** Idempotent: unsplitting an expense that isn't split just returns it. */
    @Transactional
    public ExpenseResponse unsplit(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        Expense parent = find(userId, id);
        if (parent.getParentExpenseId() != null) {
            throw ResourceNotFoundException.of("Split expense", id);
        }
        expenses.deleteAll(childrenOf(userId, parent));
        expenses.flush();
        return toResponses(userId, List.of(parent)).get(0);
    }

    // ------------------------------------------------------------------ helpers

    private Expense find(UUID userId, UUID id) {
        return expenses.findById(id)
            .filter(e -> e.getUserId().equals(userId))
            .orElseThrow(() -> ResourceNotFoundException.of("Expense", id));
    }

    private boolean hasSplits(UUID userId, Expense expense) {
        return !childrenOf(userId, expense).isEmpty();
    }

    private List<Expense> childrenOf(UUID userId, Expense parent) {
        return expenses.findByUserIdAndParentExpenseIdIn(userId, List.of(parent.getId()));
    }

    private Map<UUID, Category> visibleCategories(UUID userId) {
        return categories.findVisibleTo(userId).stream().collect(Collectors.toMap(Category::getId, c -> c));
    }

    /** The category (and optional subcategory) must be visible to the caller, the subcategory a child of it. */
    private static void requireCategories(Map<UUID, Category> visible, UUID categoryId, UUID subcategoryId) {
        if (!visible.containsKey(categoryId)) {
            throw new UnprocessableEntityException(INVALID_CATEGORY, "Unknown category " + categoryId);
        }
        if (subcategoryId != null) {
            Category sub = visible.get(subcategoryId);
            if (sub == null || !categoryId.equals(sub.getParentId())) {
                throw new UnprocessableEntityException("INVALID_SUBCATEGORY",
                    "Subcategory " + subcategoryId + " is not a child of category " + categoryId);
            }
        }
    }

    /** An explicit necessity wins; otherwise the subcategory's, then the category's default. */
    private static Necessity resolveNecessity(Necessity requested, Map<UUID, Category> visible, UUID categoryId,
                                              UUID subcategoryId) {
        requireCategories(visible, categoryId, subcategoryId);
        if (requested != null) {
            return requested;
        }
        Necessity fallback = subcategoryId != null ? visible.get(subcategoryId).getNecessityDefault() : null;
        if (fallback == null) {
            fallback = visible.get(categoryId).getNecessityDefault();
        }
        if (fallback == null) {
            throw new UnprocessableEntityException("NECESSITY_REQUIRED",
                "This category has no default necessity — specify one");
        }
        return fallback;
    }

    /** Amount must be positive and INR (v1). Refunds are recorded by the ingestion path, not typed in. */
    private static Money positive(MoneyDto dto) {
        Money money = dto.toMoney();
        if (!SUPPORTED_CURRENCY.equals(money.currency())) {
            throw new UnprocessableEntityException("UNSUPPORTED_CURRENCY", "Only INR is supported");
        }
        if (money.amount().signum() <= 0) {
            throw new BadRequestException("amount must be greater than zero");
        }
        return money;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private List<ExpenseResponse> toResponses(UUID userId, List<Expense> parents) {
        if (parents.isEmpty()) {
            return List.of();
        }
        Map<UUID, Category> names = visibleCategories(userId);
        Map<UUID, List<Expense>> childrenByParent = new HashMap<>();
        Set<UUID> parentIds = parents.stream().map(Expense::getId).collect(Collectors.toSet());
        expenses.findByUserIdAndParentExpenseIdIn(userId, parentIds).stream()
            .sorted(java.util.Comparator.comparing(Expense::getId))
            .forEach(c -> childrenByParent.computeIfAbsent(c.getParentExpenseId(), k -> new ArrayList<>()).add(c));

        return parents.stream().map(e -> {
            List<Expense> children = childrenByParent.get(e.getId());
            List<ExpenseSplitResponse> splits = children == null ? null : children.stream()
                .map(c -> new ExpenseSplitResponse(c.getId(), MoneyDto.from(c.getAmount()),
                    ref(names, c.getCategoryId()), ref(names, c.getSubcategoryId()), c.getNecessity(), c.getNote()))
                .toList();
            return new ExpenseResponse(e.getId(), e.getDate(), MoneyDto.from(e.getAmount()),
                ref(names, e.getCategoryId()), ref(names, e.getSubcategoryId()), e.getMerchant(), e.getNecessity(),
                e.getOrigin(), e.isExcludedFromHabits(), e.getTransactionId(), splits);
        }).toList();
    }

    private static CategoryRef ref(Map<UUID, Category> categories, UUID id) {
        return id == null ? null : CategoryRef.from(categories.get(id));
    }
}
