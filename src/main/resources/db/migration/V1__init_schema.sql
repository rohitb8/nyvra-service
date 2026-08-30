-- V1 — initial schema.
-- Minimal starting point: the User context. Other contexts (Accounts, Income, Expense, Portfolio,
-- Net Worth, Ingestion, Analytics) are added in later migrations following docs/engineering/DATABASE_DESIGN.md.
--
-- Conventions (see DATABASE_DESIGN.md):
--   * UUID primary keys (generated in the app)
--   * TIMESTAMPTZ for event times (UTC)
--   * enums as TEXT + CHECK
--   * every user-owned table leads its indexes with the owning key
--
-- NOTE: field-level encryption (email 🔒 + email_hash blind index) and the TimescaleDB / partitioning
-- setup are deferred to later migrations; this file keeps the skeleton runnable on plain PostgreSQL.

CREATE TABLE user_profile (
    id               UUID        PRIMARY KEY,
    keycloak_subject TEXT        NOT NULL UNIQUE,
    email            TEXT,
    display_name     TEXT,
    base_currency    CHAR(3)     NOT NULL DEFAULT 'INR',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_preferences (
    user_id                     UUID        PRIMARY KEY REFERENCES user_profile (id) ON DELETE CASCADE,
    risk_band                   TEXT        NOT NULL DEFAULT 'BALANCED'
                                    CHECK (risk_band IN ('CONSERVATIVE', 'BALANCED', 'AGGRESSIVE')),
    emergency_fund_months_target NUMERIC(4,1),
    dashboard_layout            JSONB,
    notification_channels       JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only consent ledger (DPDP). No updates; revocation sets revoked_at once.
CREATE TABLE data_consent_record (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    purpose    TEXT        NOT NULL,
    scope      JSONB,
    source     TEXT        NOT NULL CHECK (source IN ('AA', 'GMAIL', 'MANUAL')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_data_consent_record_user
    ON data_consent_record (user_id, source, granted_at DESC);
