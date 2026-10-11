package com.rohit.nyvra.analytics;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link Insight}; every query is scoped to one user. */
public interface InsightRepository extends JpaRepository<Insight, UUID> {

    /**
     * Pages a user's insights in one lifecycle state, most serious first and newest first within a
     * severity (FINANCIAL_RULES §7 precedence). Pass an unsorted {@link Pageable}.
     *
     * @param userId     the owner
     * @param status     {@code ACTIVE}, {@code DISMISSED} or {@code SUPERSEDED}, by name
     * @param severities severities to include; must not be empty
     * @param pageable   page request
     * @return one page of insights
     */
    @Query(value = """
        select i from Insight i
        where i.userId = :userId and i.severity in :severities
          and ((:status = 'ACTIVE' and i.dismissedAt is null and i.supersededAt is null)
            or (:status = 'DISMISSED' and i.dismissedAt is not null and i.supersededAt is null)
            or (:status = 'SUPERSEDED' and i.supersededAt is not null))
        order by case i.severity
            when com.rohit.nyvra.analytics.InsightSeverity.CRITICAL then 0
            when com.rohit.nyvra.analytics.InsightSeverity.WARN then 1
            else 2 end, i.raisedAt desc""",
        countQuery = """
        select count(i) from Insight i
        where i.userId = :userId and i.severity in :severities
          and ((:status = 'ACTIVE' and i.dismissedAt is null and i.supersededAt is null)
            or (:status = 'DISMISSED' and i.dismissedAt is not null and i.supersededAt is null)
            or (:status = 'SUPERSEDED' and i.supersededAt is not null))""")
    Page<Insight> search(@Param("userId") UUID userId, @Param("status") String status,
                         @Param("severities") List<InsightSeverity> severities, Pageable pageable);

    /**
     * Finds a user's insight by id.
     *
     * @param id     the insight id
     * @param userId the owner
     * @return the insight, or empty if it does not exist or belongs to someone else
     */
    Optional<Insight> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Finds the newest insight for a rule that has not been superseded (so active or dismissed).
     *
     * @param userId the owner
     * @param ruleId the rule
     * @return the newest live insight, or empty
     */
    Optional<Insight> findFirstByUserIdAndRuleIdAndSupersededAtIsNullOrderByRaisedAtDesc(UUID userId, String ruleId);
}
