package com.rohit.nyvra.goals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.AbstractEntity;

/** A manual amount the user has set aside towards a {@link Goal}. A goal's allocation is the sum of these. */
@Entity
@Table(name = "goal_contribution")
public class GoalContribution extends AbstractEntity {

    /** The goal this amount counts towards; fixed at creation. */
    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;

    /** Id of the owning user, used to scope every lookup; fixed at creation. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Amount set aside, scale 2, greater than zero. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    /** ISO 4217 code, equal to the goal's currency. */
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** Accounting date of the contribution. */
    @Column(name = "contributed_on", nullable = false, updatable = false)
    private LocalDate contributedOn;

    /** Optional free-text remark. */
    @Column(name = "note", updatable = false)
    private String note;

    /** Required by JPA; not for application use. */
    protected GoalContribution() {
        // for JPA
    }

    /**
     * Creates a contribution.
     *
     * @param goalId        the goal it counts towards, required
     * @param userId        owning user, required
     * @param amount        amount set aside, required and positive
     * @param contributedOn accounting date, required
     * @param note          optional remark
     * @throws IllegalArgumentException if the amount is not positive
     */
    public GoalContribution(UUID goalId, UUID userId, Money amount, LocalDate contributedOn, String note) {
        this.goalId = Objects.requireNonNull(goalId, "goalId");
        this.userId = Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(amount, "amount");
        if (amount.amount().signum() <= 0) {
            throw new IllegalArgumentException("Contribution must be greater than zero");
        }
        this.amount = amount.amount();
        this.currency = amount.currency();
        this.contributedOn = Objects.requireNonNull(contributedOn, "contributedOn");
        this.note = note;
    }

    /** @return the goal id */
    public UUID getGoalId() {
        return goalId;
    }

    /** @return the owning user id */
    public UUID getUserId() {
        return userId;
    }

    /** @return the amount set aside */
    public Money getAmount() {
        return Money.of(amount, currency);
    }

    /** @return the accounting date */
    public LocalDate getContributedOn() {
        return contributedOn;
    }

    /** @return the remark, or {@code null} */
    public String getNote() {
        return note;
    }
}
