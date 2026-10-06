package com.rohit.nyvra.expense;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategorisationRuleRepository extends JpaRepository<CategorisationRule, UUID> {

    /** Rules that apply to this user in evaluation order: user rules before system rules, then priority. */
    @Query("""
        SELECT r FROM CategorisationRule r
        WHERE r.userId IS NULL OR r.userId = :userId
        ORDER BY CASE WHEN r.userId IS NULL THEN 1 ELSE 0 END, r.priority DESC
        """)
    List<CategorisationRule> findApplicableTo(@Param("userId") UUID userId);
}
