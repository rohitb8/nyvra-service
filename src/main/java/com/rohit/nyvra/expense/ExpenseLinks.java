package com.rohit.nyvra.expense;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The expense module's public lookup for the id-only link from an Accounts transaction to the expense derived
 * from it (there is no foreign key — ARCHITECTURE.md rule 6). Lets the transaction feed expose
 * {@code expenseId} without touching expense internals.
 */
@Service
public class ExpenseLinks {

    private final ExpenseRepository expenses;

    public ExpenseLinks(ExpenseRepository expenses) {
        this.expenses = expenses;
    }

    /** @return transaction id → expense id, only for transactions the user has an expense for */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> expenseIdsByTransactionId(UUID userId, Collection<UUID> transactionIds) {
        Map<UUID, UUID> links = new HashMap<>();
        if (transactionIds.isEmpty()) {
            return links;
        }
        expenses.findByUserIdAndTransactionIdIn(userId, transactionIds)
            .forEach(e -> links.putIfAbsent(e.getTransactionId(), e.getId()));
        return links;
    }
}
