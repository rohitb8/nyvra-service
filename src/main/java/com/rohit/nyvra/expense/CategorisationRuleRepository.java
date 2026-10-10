package com.rohit.nyvra.expense;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link CategorisationRule}. */
public interface CategorisationRuleRepository extends JpaRepository<CategorisationRule, UUID> {

    /**
     * Rules that apply to this user in evaluation order: user rules before system rules, then priority.
     *
     * @param userId the user
     * @return the user's rules followed by the system rules
     */
    @Query("""
        SELECT r FROM CategorisationRule r
        WHERE r.userId IS NULL OR r.userId = :userId
        ORDER BY CASE WHEN r.userId IS NULL THEN 1 ELSE 0 END, r.priority DESC
        """)
    List<CategorisationRule> findApplicableTo(@Param("userId") UUID userId);

    /**
     * One page of the user's own rules; system rules are never returned.
     *
     * @param userId   the owner
     * @param pageable page and sort
     * @return the page
     */
    Page<CategorisationRule> findByUserId(UUID userId, Pageable pageable);

    /**
     * One of the user's own rules.
     *
     * @param id     the rule id
     * @param userId the owner
     * @return the rule, or empty if it does not exist or is someone else's or a system rule
     */
    Optional<CategorisationRule> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Whether the user already has a rule with this matcher.
     *
     * @param userId       the owner
     * @param matcherType  the matcher type
     * @param matcherValue the matcher value
     * @return true if one exists
     */
    boolean existsByUserIdAndMatcherTypeAndMatcherValue(UUID userId, MatcherType matcherType, String matcherValue);

    /**
     * Whether any rule targets the category.
     *
     * @param categoryId the category
     * @return true if at least one rule points at it
     */
    boolean existsByCategoryId(UUID categoryId);
}
