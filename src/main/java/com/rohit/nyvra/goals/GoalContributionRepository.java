package com.rohit.nyvra.goals;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for {@link GoalContribution}. */
public interface GoalContributionRepository extends JpaRepository<GoalContribution, UUID> {

    /** Sum of the contributions to one goal. */
    interface GoalTotal {

        /** @return the goal id */
        UUID getGoalId();

        /** @return the summed amount, scale 2 */
        BigDecimal getTotal();
    }

    /**
     * Finds a contribution of the given goal.
     *
     * @param id     the contribution id
     * @param goalId the goal it must belong to
     * @return the contribution, or empty when there is none with that id on that goal
     */
    Optional<GoalContribution> findByIdAndGoalId(UUID id, UUID goalId);

    /**
     * Lists a goal's contributions.
     *
     * @param goalId   the goal
     * @param pageable paging and sorting
     * @return one page of contributions
     */
    Page<GoalContribution> findByGoalId(UUID goalId, Pageable pageable);

    /**
     * Sums contributions per goal in one query, so a list of goals needs no per-row lookup. Goals without
     * contributions are absent from the result.
     *
     * @param goalIds the goals
     * @return one total per goal that has contributions
     */
    @Query("select c.goalId as goalId, sum(c.amount) as total from GoalContribution c "
        + "where c.goalId in :goalIds group by c.goalId")
    List<GoalTotal> totalsByGoalIds(@Param("goalIds") Collection<UUID> goalIds);
}
