-- V24 — Goals context: goal, goal_contribution.
-- Straight-line progress (HEALTH_SCORE_SPEC.md §3.6) is computed in the app from these rows; nothing derived is stored.

CREATE TABLE goal (
    id            UUID          PRIMARY KEY,
    user_id       UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    name          TEXT          NOT NULL,
    type          TEXT          NOT NULL
                      CHECK (type IN ('EMERGENCY_FUND', 'HOME', 'VEHICLE', 'EDUCATION', 'RETIREMENT',
                                      'TRAVEL', 'WEDDING', 'OTHER')),
    target_amount NUMERIC(19,2) NOT NULL,
    currency      CHAR(3)       NOT NULL DEFAULT 'INR',
    start_date    DATE          NOT NULL,
    target_date   DATE          NOT NULL,
    weight        INTEGER       NOT NULL DEFAULT 1,
    status        TEXT          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_goal_target_positive CHECK (target_amount > 0),
    CONSTRAINT chk_goal_dates CHECK (target_date > start_date),
    CONSTRAINT chk_goal_weight CHECK (weight >= 1)
);

CREATE INDEX idx_goal_user_status ON goal (user_id, status);

CREATE TABLE goal_contribution (
    id             UUID          PRIMARY KEY,
    goal_id        UUID          NOT NULL REFERENCES goal (id) ON DELETE CASCADE,
    user_id        UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    amount         NUMERIC(19,2) NOT NULL,
    currency       CHAR(3)       NOT NULL DEFAULT 'INR',
    contributed_on DATE          NOT NULL,
    note           TEXT,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_goal_contribution_positive CHECK (amount > 0)
);

CREATE INDEX idx_goal_contribution_goal ON goal_contribution (goal_id, contributed_on DESC);
