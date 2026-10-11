-- V20 — Analytics: store the full health score explanation and stamp the ruleset on insights.
-- HEALTH_SCORE_SPEC.md §1/§2: topDrivers and the applied hard cap are part of a score, and
-- FINANCIAL_RULES.md requires ruleset_version on every health_score and insight row. All columns are
-- nullable additions so rows written before this migration stay valid.

ALTER TABLE health_score
    ADD COLUMN top_drivers        JSONB,
    ADD COLUMN cap_value          NUMERIC(5,2),
    ADD COLUMN cap_reason         TEXT,
    ADD COLUMN ruleset_version    TEXT,
    ADD COLUMN score_spec_version TEXT,
    ADD CONSTRAINT ck_health_score_cap CHECK (
        (cap_value IS NULL AND cap_reason IS NULL) OR (cap_value IS NOT NULL AND cap_reason IS NOT NULL));

ALTER TABLE insight
    ADD COLUMN ruleset_version TEXT;
