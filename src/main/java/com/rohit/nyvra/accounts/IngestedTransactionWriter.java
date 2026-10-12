package com.rohit.nyvra.accounts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.partition.MonthlyPartitions;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.expense.IngestedExpenseRecorder;
import com.rohit.nyvra.ingestion.IngestedDirection;
import com.rohit.nyvra.ingestion.IngestedTransaction;
import com.rohit.nyvra.ingestion.TransactionsIngested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumes {@link TransactionsIngested} and writes the transactions to the ledger, then hands the debits to the
 * expense module to be categorised. This lives in the accounts module, not in ingestion, because ingestion
 * reaches downstream modules by events only (ARCHITECTURE.md rule 1).
 *
 * <p>Idempotent on {@link IngestedTransaction#dedupKey()}: a transaction already in the ledger for the same value
 * date is skipped, so repeated fetches and re-normalisation never double-count. The event is handled after the
 * publishing transaction commits, in a transaction of its own; a failure is logged and the fetch is unaffected,
 * and re-normalising the fetch replays the event safely.
 *
 * <p>The account is found by its masked number among the user's AA-sourced accounts and created as a savings
 * account when it is not known yet. Credits are ledgered but not turned into income here.
 */
@Component
public class IngestedTransactionWriter {

    /** Logger. */
    private static final Logger log = LoggerFactory.getLogger(IngestedTransactionWriter.class);

    /** Ledger persistence. */
    private final AccountTransactionRepository transactions;

    /** Account persistence. */
    private final FinancialAccountRepository accounts;

    /** Creates the monthly partitions an insert needs. */
    private final MonthlyPartitions partitions;

    /** Categorises debits into expenses. */
    private final IngestedExpenseRecorder expenseRecorder;

    /** Runs {@link #write} in a transaction of its own; a template because a self-call would bypass a proxy. */
    private final TransactionTemplate transaction;

    /**
     * Creates the writer.
     *
     * @param transactions    ledger persistence
     * @param accounts        account persistence
     * @param partitions      monthly partition creator
     * @param expenseRecorder expense module entry point for ingested debits
     * @param txManager       transaction manager for the handler's own transaction
     */
    public IngestedTransactionWriter(AccountTransactionRepository transactions, FinancialAccountRepository accounts,
                                     MonthlyPartitions partitions, IngestedExpenseRecorder expenseRecorder,
                                     PlatformTransactionManager txManager) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.partitions = partitions;
        this.expenseRecorder = expenseRecorder;
        this.transaction = new TransactionTemplate(txManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Event entry point: runs {@link #write} after the publisher commits (or immediately when there is no
     * surrounding transaction) and never lets a failure reach the publisher.
     *
     * @param event the ingested transactions of one account
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTransactionsIngested(TransactionsIngested event) {
        try {
            transaction.execute(status -> write(event));
        } catch (RuntimeException e) {
            log.error("Writing ingested transactions of fetch {} failed", event.fetchSessionId(), e);
        }
    }

    /**
     * Writes the transactions the ledger does not have yet and records expenses for the new debits. Must run
     * inside a transaction; {@link #onTransactionsIngested} provides one.
     *
     * @param event the ingested transactions of one account
     * @return how many ledger lines were created
     */
    int write(TransactionsIngested event) {
        UUID userId = event.userId();
        List<IngestedTransaction> incoming = event.transactions();
        LocalDate first = incoming.stream().map(IngestedTransaction::valueDate).min(Comparator.naturalOrder())
            .orElseThrow();
        LocalDate last = incoming.stream().map(IngestedTransaction::valueDate).max(Comparator.naturalOrder())
            .orElseThrow();
        partitions.ensureRange("transaction", first, last);

        List<IngestedTransaction> fresh = incoming.stream()
            .filter(t -> !transactions.existsByDedupKeyAndValueDate(t.dedupKey(), t.valueDate()))
            .toList();
        if (fresh.isEmpty()) {
            return 0;
        }
        FinancialAccount account = resolveAccount(userId, event.fipId(), event.accountRef(), fresh);

        List<IngestedExpenseRecorder.Debit> debits = new ArrayList<>();
        for (IngestedTransaction t : fresh) {
            Money signed = Money.of(t.amount(), Money.INR);
            if (t.direction() == IngestedDirection.DEBIT) {
                signed = signed.negate();
            }
            Money balance = t.balanceAfter() == null ? null : Money.of(t.balanceAfter(), Money.INR);
            AccountTransaction saved = transactions.save(new AccountTransaction(account.getId(), userId,
                t.valueDate(), t.valueDate(), signed, t.narration(), null, balance, RecordSource.AA, t.dedupKey()));
            if (t.direction() == IngestedDirection.DEBIT) {
                debits.add(new IngestedExpenseRecorder.Debit(saved.getId(), t.valueDate(), t.amount(), t.narration()));
            }
        }
        refreshBalance(account, fresh);
        expenseRecorder.record(userId, debits);
        return fresh.size();
    }

    /**
     * Finds the user's AA account with this masked number, creating a savings account when there is none.
     *
     * @param userId     the owner
     * @param fipId      the provider, used as the institution of a new account
     * @param accountRef the provider-side masked account reference
     * @param fresh      the transactions about to be written, used to seed a new account's balance
     * @return the account
     */
    private FinancialAccount resolveAccount(UUID userId, String fipId, String accountRef,
                                            List<IngestedTransaction> fresh) {
        Optional<FinancialAccount> existing = accounts.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId)
            .stream()
            .filter(a -> a.getSource() == RecordSource.AA && accountRef != null
                && accountRef.equals(a.getMaskedNumber()))
            .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        BigDecimal opening = latestBalance(fresh).map(IngestedTransaction::balanceAfter).orElse(BigDecimal.ZERO);
        String label = fipId == null ? accountRef : fipId + " " + accountRef;
        return accounts.save(new FinancialAccount(userId, AccountType.SAVINGS, fipId, accountRef, label,
            Money.of(opening, Money.INR), Instant.EPOCH, RecordSource.AA));
    }

    /**
     * Moves the account balance to the newest reported balance when that is newer than what the account holds.
     *
     * @param account the account
     * @param fresh   the transactions just written
     */
    private void refreshBalance(FinancialAccount account, List<IngestedTransaction> fresh) {
        latestBalance(fresh).ifPresent(t -> {
            Instant asOf = t.valueDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            if (asOf.isAfter(account.getBalanceAsOf())) {
                account.updateBalance(Money.of(t.balanceAfter(), Money.INR), asOf);
                accounts.save(account);
            }
        });
    }

    /**
     * The transaction with the newest value date that reports a balance; later entries win a tie.
     *
     * @param list the transactions
     * @return that transaction, or empty when none reports a balance
     */
    private static Optional<IngestedTransaction> latestBalance(List<IngestedTransaction> list) {
        IngestedTransaction best = null;
        for (IngestedTransaction t : list) {
            if (t.balanceAfter() != null && (best == null || !t.valueDate().isBefore(best.valueDate()))) {
                best = t;
            }
        }
        return Optional.ofNullable(best);
    }
}
