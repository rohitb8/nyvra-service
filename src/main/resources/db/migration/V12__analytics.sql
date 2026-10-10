-- V12 — Analytics context: health_score, insight, dashboard_summary_cache.
-- Target: database/schema.dbml (Analytics + Health Score). Conventions: database/decisions.md.
-- health_score is a TimescaleDB hypertable from creation (decisions.md §3).
-- Schema only: no entities, services or endpoints yet. health_score stores the output of
-- HEALTH_SCORE_SPEC.md; it never defines the formula.

-- Append-only time series; no id/created_at/updated_at.
CREATE TABLE health_score (
    user_id     UUID         NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    as_of       TIMESTAMPTZ  NOT NULL,
    overall     NUMERIC(5,2) NOT NULL CHECK (overall BETWEEN 0 AND 100),
    band        TEXT         NOT NULL CHECK (band IN ('NEEDS_ATTENTION', 'FAIR', 'GOOD', 'EXCELLENT')),
    sub_scores  JSONB        NOT NULL,
    confidence  NUMERIC(4,3) CHECK (confidence BETWEEN 0 AND 1),
    inputs_hash TEXT,
    PRIMARY KEY (user_id, as_of)
);

SELECT create_hypertable('health_score', 'as_of', chunk_time_interval => INTERVAL '90 days');

-- A changed insight supersedes the previous one rather than duplicating it.
CREATE TABLE insight (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    rule_id       TEXT        NOT NULL,
    severity      TEXT        NOT NULL CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
    message       TEXT        NOT NULL,
    evidence      JSONB,
    raised_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    dismissed_at  TIMESTAMPTZ,
    superseded_at TIMESTAMPTZ
);

CREATE INDEX idx_insight_user_raised ON insight (user_id, raised_at);
-- At most one active (not dismissed, not superseded) insight per user and rule.
CREATE UNIQUE INDEX uq_insight_active_rule ON insight (user_id, rule_id)
    WHERE dismissed_at IS NULL AND superseded_at IS NULL;

-- Redis is primary; this is the durable rebuild source only.
CREATE TABLE dashboard_summary_cache (
    user_id     UUID        PRIMARY KEY REFERENCES user_profile (id) ON DELETE RESTRICT,
    payload     JSONB,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
