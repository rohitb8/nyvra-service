package com.rohit.nyvra.analytics;

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

import com.rohit.nyvra.accounts.FinancialAccount;
import com.rohit.nyvra.expense.Expense;
import com.rohit.nyvra.goals.Goal;
import com.rohit.nyvra.goals.GoalContribution;
import com.rohit.nyvra.income.IncomeEntry;
import com.rohit.nyvra.portfolio.PortfolioHolding;
import com.rohit.nyvra.user.UserProfile;

/**
 * Marks a user's health score stale whenever something it depends on is written: income entries,
 * expenses, accounts, portfolio holdings, goals and goal contributions, or when the user's profile changes (the
 * date of birth sets the equity target). A new profile is not marked: it holds no data to score yet. Listening at the persistence layer,
 * as net worth does, means those modules need no hooks and every write path (API, ingestion, jobs) is
 * covered. It only records "dirty"; the recompute happens later in {@link HealthScoreRecomputer#flushDue},
 * after the debounce period, so it never runs inside the caller's transaction.
 */
@Component
public class HealthScoreChangeListener
    implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    /** Receives the dirty marks. */
    private final HealthScoreRecomputer recomputer;
    /** Source of the Hibernate session factory the listeners are registered on. */
    private final EntityManagerFactory entityManagerFactory;

    /**
     * Creates the listener.
     *
     * @param recomputer           the recomputer to notify
     * @param entityManagerFactory the JPA factory whose Hibernate events are observed
     */
    public HealthScoreChangeListener(HealthScoreRecomputer recomputer, EntityManagerFactory entityManagerFactory) {
        this.recomputer = recomputer;
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
     * Marks the owner of a newly inserted entity.
     *
     * @param event the insert event
     */
    @Override
    public void onPostInsert(PostInsertEvent event) {
        touch(event.getEntity());
    }

    /**
     * Marks the owner of an updated entity, or the user themselves when their profile changed.
     *
     * @param event the update event
     */
    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        if (event.getEntity() instanceof UserProfile profile) {
            recomputer.markDirty(profile.getId());
            return;
        }
        touch(event.getEntity());
    }

    /**
     * Marks the owner of a deleted entity.
     *
     * @param event the delete event
     */
    @Override
    public void onPostDelete(PostDeleteEvent event) {
        touch(event.getEntity());
    }

    /**
     * Tells Hibernate the listener does not need to wait for commit.
     *
     * @param persister the entity persister
     * @return always {@code false}
     */
    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    /**
     * Marks the entity's owner dirty if the entity is one the score depends on.
     *
     * @param entity the changed entity; anything the score does not read is ignored
     */
    private void touch(Object entity) {
        UUID userId = switch (entity) {
            case IncomeEntry e -> e.getUserId();
            case Expense e -> e.getUserId();
            case FinancialAccount a -> a.getUserId();
            case PortfolioHolding h -> h.getUserId();
            case Goal g -> g.getUserId();
            case GoalContribution c -> c.getUserId();
            default -> null;
        };
        if (userId != null) {
            recomputer.markDirty(userId);
        }
    }
}
