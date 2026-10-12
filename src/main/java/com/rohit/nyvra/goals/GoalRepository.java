package com.rohit.nyvra.goals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Persistence access for {@link Goal}; every API read takes the user id so other users' rows stay invisible. */
public interface GoalRepository extends JpaRepository<Goal, UUID>, JpaSpecificationExecutor<Goal> {

    /**
     * Finds a goal owned by the given user.
     *
     * @param id     the goal id
     * @param userId the owning user
     * @return the goal, or empty when it does not exist or belongs to someone else
     */
    Optional<Goal> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Lists a user's goals in one status.
     *
     * @param userId the owning user
     * @param status the lifecycle state
     * @return the matching goals, in no particular order
     */
    List<Goal> findByUserIdAndStatus(UUID userId, GoalStatus status);
}
