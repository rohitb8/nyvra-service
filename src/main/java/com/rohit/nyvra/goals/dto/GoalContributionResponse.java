package com.rohit.nyvra.goals.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.goals.GoalContribution;

/**
 * API view of a goal contribution.
 *
 * @param id            contribution id
 * @param goalId        the goal it counts towards
 * @param amount        amount set aside
 * @param contributedOn accounting date
 * @param note          remark; omitted from the JSON when none was given
 * @param createdAt     creation time (UTC)
 */
public record GoalContributionResponse(
    UUID id,
    UUID goalId,
    MoneyDto amount,
    LocalDate contributedOn,
    @JsonInclude(JsonInclude.Include.NON_NULL) String note,
    Instant createdAt) {

    /**
     * Maps a contribution to its response.
     *
     * @param c the contribution
     * @return the response
     */
    public static GoalContributionResponse from(GoalContribution c) {
        return new GoalContributionResponse(c.getId(), c.getGoalId(), MoneyDto.from(c.getAmount()),
            c.getContributedOn(), c.getNote(), c.getCreatedAt());
    }
}
