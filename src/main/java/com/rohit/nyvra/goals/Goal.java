package com.rohit.nyvra.goals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.AbstractEntity;

/**
 * A savings goal: a target amount to reach by a target date, measured from a start date. The target date is
 * always after the start date — mirrored by {@code chk_goal_dates}.
 */
@Entity
@Table(name = "goal")
public class Goal extends AbstractEntity {

    /** Id of the owning user, used to scope every lookup; fixed at creation. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** User-facing label. */
    @Column(name = "name", nullable = false)
    private String name;

    /** What the goal is for. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private GoalType type;

    /** Amount to reach, scale 2, greater than zero. */
    @Column(name = "target_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal targetAmount;

    /** ISO 4217 code of the goal and of every contribution to it; fixed at creation. */
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** First day of the accrual period; fixed at creation. */
    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    /** Day by which the target should be reached. */
    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    /** Relative importance among the user's goals in the health score; at least 1. */
    @Column(name = "weight", nullable = false)
    private int weight;

    /** Lifecycle state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GoalStatus status;

    /** Required by JPA; not for application use. */
    protected Goal() {
        // for JPA
    }

    /**
     * Creates an active goal.
     *
     * @param userId       owning user, required
     * @param name         label, required
     * @param type         what the goal is for, required
     * @param targetAmount amount to reach, required and positive
     * @param startDate    first day of accrual, required
     * @param targetDate   day by which to reach the target, required and after {@code startDate}
     * @param weight       relative importance, at least 1
     * @throws IllegalArgumentException if the amount is not positive, the weight is below 1 or the dates are not ordered
     */
    public Goal(UUID userId, String name, GoalType type, Money targetAmount, LocalDate startDate,
                LocalDate targetDate, int weight) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetAmount, "targetAmount");
        this.currency = targetAmount.currency();
        this.startDate = Objects.requireNonNull(startDate, "startDate");
        this.status = GoalStatus.ACTIVE;
        revise(name, type, targetAmount, targetDate, weight);
    }

    /**
     * Replaces the editable fields together so the invariants always hold for the combined result.
     *
     * @param name         new label, required
     * @param type         new type, required
     * @param targetAmount new target in the goal's currency, required and positive
     * @param targetDate   new target date, required and after the start date
     * @param weight       new weight, at least 1
     * @throws IllegalArgumentException if the amount is not positive or in another currency, the weight is below 1
     *     or the target date is not after the start date
     */
    public void revise(String name, GoalType type, Money targetAmount, LocalDate targetDate, int weight) {
        Objects.requireNonNull(targetAmount, "targetAmount");
        Objects.requireNonNull(targetDate, "targetDate");
        if (targetAmount.amount().signum() <= 0) {
            throw new IllegalArgumentException("Target amount must be greater than zero");
        }
        if (!targetAmount.currency().equals(currency)) {
            throw new IllegalArgumentException("Target amount currency must be " + currency);
        }
        if (!targetDate.isAfter(startDate)) {
            throw new IllegalArgumentException("Target date must be after the start date");
        }
        if (weight < 1) {
            throw new IllegalArgumentException("Weight must be at least 1");
        }
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.targetAmount = targetAmount.amount();
        this.targetDate = targetDate;
        this.weight = weight;
    }

    /**
     * Moves the goal between {@code ACTIVE} and {@code ARCHIVED}.
     *
     * @param status the new state, required
     */
    public void changeStatus(GoalStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    /** @return the owning user id */
    public UUID getUserId() {
        return userId;
    }

    /** @return the label */
    public String getName() {
        return name;
    }

    /** @return what the goal is for */
    public GoalType getType() {
        return type;
    }

    /** @return the amount to reach, in the goal's currency */
    public Money getTargetAmount() {
        return Money.of(targetAmount, currency);
    }

    /** @return the ISO 4217 currency code */
    public String getCurrency() {
        return currency;
    }

    /** @return the first day of accrual */
    public LocalDate getStartDate() {
        return startDate;
    }

    /** @return the day by which the target should be reached */
    public LocalDate getTargetDate() {
        return targetDate;
    }

    /** @return the relative importance in the health score */
    public int getWeight() {
        return weight;
    }

    /** @return the lifecycle state */
    public GoalStatus getStatus() {
        return status;
    }
}
