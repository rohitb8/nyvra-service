package com.rohit.nyvra.networth;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.internal.SessionFactoryImpl;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.rohit.nyvra.accounts.FinancialAccount;
import com.rohit.nyvra.portfolio.PortfolioHolding;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps today's net-worth snapshot current when the inputs change. It listens to Hibernate for inserts,
 * updates and deletes of accounts and portfolio holdings, remembers which users were touched, and after the
 * surrounding transaction commits re-records each such user's snapshot. Manual entries already refresh the
 * snapshot themselves, and price quotes are covered by the daily job, so neither is watched here.
 *
 * <p>Listening at the persistence layer means the accounts and portfolio modules need no hooks and no
 * knowledge of net worth, and every write path (API, ingestion, future jobs) is covered. The recompute runs
 * in its own transaction after commit and never fails the caller: a failure is logged and the next change or
 * the daily job repairs the snapshot.
 */
@Slf4j
@Component
public class NetWorthChangeListener
    implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    /** Opens the independent transaction the recompute runs in. */
    private final TransactionTemplate afterCommit;
    /** Recomputes and stores the snapshot. */
    private final NetWorthService netWorth;
    /** Source of the Hibernate session factory the listeners are registered on. */
    private final EntityManagerFactory entityManagerFactory;

    /**
     * Creates the listener.
     *
     * @param transactionManager   the transaction manager used for the recompute transaction
     * @param netWorth             the net-worth service
     * @param entityManagerFactory the JPA factory whose Hibernate events are observed
     */
    public NetWorthChangeListener(PlatformTransactionManager transactionManager, NetWorthService netWorth,
                                  EntityManagerFactory entityManagerFactory) {
        this.afterCommit = new TransactionTemplate(transactionManager);
        this.afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.netWorth = netWorth;
        this.entityManagerFactory = entityManagerFactory;
    }

    /** Registers this listener for post-insert, post-update and post-delete events. */
    @PostConstruct
    void register() {
        EventListenerRegistry registry = entityManagerFactory.unwrap(SessionFactoryImpl.class)
            .getServiceRegistry().requireService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    /**
     * Notes the owner of a newly inserted account or holding.
     *
     * @param event the insert event
     */
    @Override
    public void onPostInsert(PostInsertEvent event) {
        touch(event.getEntity());
    }

    /**
     * Notes the owner of an updated account or holding.
     *
     * @param event the update event
     */
    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        touch(event.getEntity());
    }

    /**
     * Notes the owner of a deleted account or holding.
     *
     * @param event the delete event
     */
    @Override
    public void onPostDelete(PostDeleteEvent event) {
        touch(event.getEntity());
    }

    /**
     * Tells Hibernate the listener does not need to wait for commit; the commit hook is registered separately.
     *
     * @param persister the entity persister
     * @return always {@code false}
     */
    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    /**
     * Records that the entity's owner needs a fresh snapshot once the transaction commits.
     *
     * @param entity the changed entity; anything other than an account or holding is ignored
     */
    private void touch(Object entity) {
        UUID userId;
        if (entity instanceof FinancialAccount account) {
            userId = account.getUserId();
        } else if (entity instanceof PortfolioHolding holding) {
            userId = holding.getUserId();
        } else {
            return;
        }
        if (userId == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        Recompute recompute = TransactionSynchronizationManager.getSynchronizations().stream()
            .filter(Recompute.class::isInstance).map(Recompute.class::cast).findFirst().orElse(null);
        if (recompute == null) {
            recompute = new Recompute();
            TransactionSynchronizationManager.registerSynchronization(recompute);
        }
        recompute.users.add(userId);
    }

    /** Recomputes the touched users' snapshots after the surrounding transaction ends. */
    private final class Recompute implements TransactionSynchronization {

        /** Users to recompute, filled while the transaction runs; Hibernate may flush during commit itself. */
        private final Set<UUID> users = new LinkedHashSet<>();

        /** Re-records each touched user's snapshot in its own transaction, logging instead of throwing. */
        @Override
        public void afterCommit() {
            for (UUID userId : users) {
                try {
                    afterCommit.executeWithoutResult(status -> netWorth.recordSnapshot(userId));
                } catch (RuntimeException e) {
                    log.warn("Net-worth recompute failed for user {}", userId, e);
                }
            }
        }
    }
}
