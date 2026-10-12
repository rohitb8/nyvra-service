package com.rohit.nyvra.accounts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.ingestion.AccountSnapshotIngested;
import com.rohit.nyvra.ingestion.PayloadType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes {@link AccountSnapshotIngested} and keeps the matching account's balance current: term and
 * recurring deposits, EPF and NPS become asset accounts, loans and credit cards become liability accounts
 * (the net-worth calculator counts those by magnitude). Net worth needs no hook of its own, because it
 * re-records the user's snapshot whenever an account changes. This lives in the accounts module because
 * ingestion reaches downstream modules by events only (ARCHITECTURE.md rule 1).
 *
 * <p>Idempotent: an account is found by user, AA source, masked number and type, and its balance moves only
 * when the snapshot is not older than the balance it already holds, so replays and out-of-order fetches
 * converge on the newest figure. The event is handled after the publisher commits, in a transaction of its
 * own, and a failure is logged without touching the fetch.
 */
@Component
public class IngestedSnapshotWriter {

    /** Logger. */
    private static final Logger log = LoggerFactory.getLogger(IngestedSnapshotWriter.class);

    /** Account persistence. */
    private final FinancialAccountRepository accounts;

    /** Runs {@link #apply} in a transaction of its own; a template because a self-call would bypass a proxy. */
    private final TransactionTemplate transaction;

    /**
     * Creates the writer.
     *
     * @param accounts  account persistence
     * @param txManager transaction manager for the handler's own transaction
     */
    public IngestedSnapshotWriter(FinancialAccountRepository accounts, PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.transaction = new TransactionTemplate(txManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Event entry point: runs {@link #apply} after the publisher commits (or at once when there is no
     * surrounding transaction) and never lets a failure reach the publisher.
     *
     * @param event the snapshot of one account
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountSnapshotIngested(AccountSnapshotIngested event) {
        try {
            transaction.executeWithoutResult(status -> apply(event));
        } catch (RuntimeException e) {
            log.error("Applying account snapshot of fetch {} failed", event.fetchSessionId(), e);
        }
    }

    /**
     * Creates or refreshes the account the snapshot describes. Must run inside a transaction.
     *
     * @param event the snapshot of one account
     * @return {@code true} when an account was created or its balance changed
     */
    boolean apply(AccountSnapshotIngested event) {
        AccountType type = accountType(event.payloadType());
        if (type == null) {
            log.warn("Ignoring account snapshot of unsupported type {} in fetch {}", event.payloadType(),
                event.fetchSessionId());
            return false;
        }
        Instant asOf = event.asOf() != null ? event.asOf() : Instant.now();
        Money balance = Money.of(event.amount(), Money.INR);
        FinancialAccount account = find(event.userId(), event.accountRef(), type);
        if (account == null) {
            String label = event.fipId() == null ? event.accountRef() : event.fipId() + " " + event.accountRef();
            accounts.save(new FinancialAccount(event.userId(), type, event.fipId(), event.accountRef(), label,
                balance, asOf, RecordSource.AA));
            return true;
        }
        if (asOf.isBefore(account.getBalanceAsOf())
            || (account.getCurrentBalance().amount().compareTo(balance.amount()) == 0
                && asOf.equals(account.getBalanceAsOf()))) {
            return false;
        }
        account.updateBalance(balance, asOf);
        accounts.save(account);
        return true;
    }

    /**
     * Finds the user's AA account with this masked number and type.
     *
     * @param userId     the owner
     * @param accountRef provider-side masked account reference
     * @param type       the account type
     * @return the account, or {@code null} when there is none
     */
    private FinancialAccount find(UUID userId, String accountRef, AccountType type) {
        if (accountRef == null) {
            return null;
        }
        List<FinancialAccount> mine = accounts.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId);
        return mine.stream()
            .filter(a -> a.getSource() == RecordSource.AA && a.getType() == type
                && accountRef.equals(a.getMaskedNumber()))
            .findFirst().orElse(null);
    }

    /**
     * Maps an ingestion payload type to the account type it is stored as.
     *
     * @param payloadType the payload type of the snapshot
     * @return the account type, or {@code null} for payload types that are not account snapshots
     */
    private static AccountType accountType(PayloadType payloadType) {
        return switch (payloadType) {
            case TERM_DEPOSIT -> AccountType.TERM_DEPOSIT;
            case RECURRING_DEPOSIT -> AccountType.RECURRING_DEPOSIT;
            case EPF -> AccountType.EPF;
            case NPS -> AccountType.NPS;
            case LOAN -> AccountType.LOAN;
            case CREDIT_CARD -> AccountType.CREDIT_CARD;
            case DEPOSIT, MUTUAL_FUND, EQUITIES -> null;
        };
    }
}
