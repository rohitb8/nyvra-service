-- V11 — Net Worth context: net_worth_snapshot, manual_asset_liability.
-- Target: database/schema.dbml (Net Worth). Conventions: database/decisions.md.
-- net_worth_snapshot is a TimescaleDB hypertable from creation (decisions.md §3).
-- Schema only: no entities, services or endpoints yet.

-- Append-only time series; no id/created_at/updated_at. as_of is written at the start of the
-- snapshot day by the app (event-driven recompute + daily job), so the PK gives one row per day.
CREATE TABLE net_worth_snapshot (
    user_id             UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    as_of               TIMESTAMPTZ   NOT NULL,
    total_assets        NUMERIC(19,2) NOT NULL,
    total_liabilities   NUMERIC(19,2) NOT NULL,
    net_worth           NUMERIC(19,2) NOT NULL,
    breakdown           JSONB,
    contributing_sources JSONB,
    PRIMARY KEY (user_id, as_of),
    CONSTRAINT chk_net_worth_snapshot_net_worth CHECK (net_worth = total_assets - total_liabilities)
);

SELECT create_hypertable('net_worth_snapshot', 'as_of', chunk_time_interval => INTERVAL '30 days');

-- Assets/liabilities the user maintains by hand (property, vehicle, private loans...).
-- The app flags a row STALE once revaluation_cadence has elapsed since value_as_of.
CREATE TABLE manual_asset_liability (
    id                  UUID          PRIMARY KEY,
    user_id             UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    kind                TEXT          NOT NULL CHECK (kind IN ('ASSET', 'LIABILITY')),
    class               TEXT          NOT NULL
                            CHECK (class IN ('REAL_ESTATE', 'VEHICLE', 'JEWELLERY', 'PRIVATE_EQUITY',
                                             'PERSONAL_LOAN', 'OTHER')),
    label               TEXT,
    value               NUMERIC(19,2) NOT NULL CHECK (value >= 0),
    currency            CHAR(3)       NOT NULL DEFAULT 'INR',
    value_as_of         DATE          NOT NULL,
    revaluation_cadence INTERVAL,
    deleted_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_manual_asset_liability_user ON manual_asset_liability (user_id) WHERE deleted_at IS NULL;
