package com.rohit.nyvra.accounts;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rohit.nyvra.accounts.dto.TransactionResponse;
import com.rohit.nyvra.common.api.CursorPage;
import com.rohit.nyvra.common.api.DateIdCursor;
import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.expense.ExpenseLinks;
import com.rohit.nyvra.user.CurrentUserService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the ledger for the signed-in user. The feed is newest first ({@code valueDate DESC, id DESC})
 * with keyset pagination, so deep pages cost the same as the first and date filters prune partitions.
 */
@Service
public class TransactionService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("valueDate"), Sort.Order.desc("id"));

    private final AccountTransactionRepository transactions;
    private final FinancialAccountRepository accounts;
    private final ExpenseLinks expenseLinks;
    private final CurrentUserService currentUser;

    public TransactionService(AccountTransactionRepository transactions, FinancialAccountRepository accounts,
                              ExpenseLinks expenseLinks, CurrentUserService currentUser) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.expenseLinks = expenseLinks;
        this.currentUser = currentUser;
    }

    /** @param accountIds restricts to these accounts; ids that aren't the caller's simply match nothing */
    @Transactional(readOnly = true)
    public CursorPage<TransactionResponse> list(Collection<UUID> accountIds, LocalDate from, LocalDate to,
                                                TransactionDirection direction, String cursor, int limit) {
        UUID userId = currentUser.currentUser().getId();
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("'from' must not be after 'to'");
        }
        String fingerprint = "a=%s;f=%s;t=%s;d=%s".formatted(
            accountIds == null ? "" : accountIds.stream().map(UUID::toString).sorted().toList(), from, to, direction);
        DateIdCursor after = cursor == null || cursor.isBlank()
            ? null : DateIdCursor.decode(cursor, fingerprint);

        Specification<AccountTransaction> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("userId"), userId));
            if (accountIds != null && !accountIds.isEmpty()) {
                where.add(root.get("accountId").in(accountIds));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("valueDate"), from));
            }
            if (to != null) {
                where.add(cb.lessThanOrEqualTo(root.<LocalDate>get("valueDate"), to));
            }
            if (direction != null) {
                where.add(cb.equal(root.get("direction"), direction));
            }
            if (after != null) {
                where.add(cb.or(
                    cb.lessThan(root.<LocalDate>get("valueDate"), after.date()),
                    cb.and(
                        cb.equal(root.get("valueDate"), after.date()),
                        cb.lessThan(root.<UUID>get("id"), after.id()))));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };

        // Fetch one extra row to learn whether another page exists, without a count query.
        List<AccountTransaction> rows = transactions.findBy(spec, q -> q.sortBy(NEWEST_FIRST).limit(limit + 1).all());
        boolean hasMore = rows.size() > limit;
        List<AccountTransaction> page = hasMore ? rows.subList(0, limit) : rows;
        String next = null;
        if (hasMore) {
            AccountTransaction last = page.get(page.size() - 1);
            next = new DateIdCursor(last.getValueDate(), last.getId()).encode(fingerprint);
        }
        Map<UUID, UUID> links = expenseLinks.expenseIdsByTransactionId(userId, page.stream().map(AccountTransaction::getId).toList());
        return new CursorPage<>(page.stream().map(t -> TransactionResponse.from(t, links.get(t.getId()))).toList(), next, limit);
    }

    /** One account's ledger; 404 if the account isn't the caller's (or was deleted). */
    @Transactional(readOnly = true)
    public CursorPage<TransactionResponse> listForAccount(UUID accountId, LocalDate from, LocalDate to,
                                                          TransactionDirection direction, String cursor, int limit) {
        UUID userId = currentUser.currentUser().getId();
        accounts.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Account", accountId));
        return list(List.of(accountId), from, to, direction, cursor, limit);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        return transactions.findById(id)
            .filter(t -> t.getUserId().equals(userId))
            .map(t -> TransactionResponse.from(t,
                expenseLinks.expenseIdsByTransactionId(userId, List.of(id)).get(id)))
            .orElseThrow(() -> ResourceNotFoundException.of("Transaction", id));
    }
}
