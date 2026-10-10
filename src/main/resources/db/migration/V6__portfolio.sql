-- V6 — Portfolio context: instrument, portfolio_holding, corporate_action, price_quote, valuation_snapshot.
-- Target: database/schema.dbml (Portfolio). Conventions: database/decisions.md.
-- price_quote and valuation_snapshot are TimescaleDB hypertables from creation (decisions.md §3).
-- Continuous aggregates (price_quote_daily) are deliberately not part of this migration.

-- Reference data shared by all users — no user_id.
CREATE TABLE instrument (
    id          UUID        PRIMARY KEY,
    isin        CHAR(12)    UNIQUE,
    symbol      TEXT,
    name        TEXT,
    asset_class TEXT        NOT NULL
                    CHECK (asset_class IN ('EQUITY', 'MUTUAL_FUND', 'BOND', 'NPS', 'EPF', 'FOREIGN_EQUITY',
                                           'CASH', 'GOLD', 'REIT')),
    currency    CHAR(3)     NOT NULL DEFAULT 'INR',
    country     CHAR(2),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_instrument_symbol ON instrument (symbol) WHERE symbol IS NOT NULL;

-- quantity = 0 with closed_at set is a closed position, kept for XIRR history.
CREATE TABLE portfolio_holding (
    id            UUID          PRIMARY KEY,
    user_id       UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    instrument_id UUID          NOT NULL REFERENCES instrument (id) ON DELETE RESTRICT,
    asset_class   TEXT          NOT NULL
                      CHECK (asset_class IN ('EQUITY', 'MUTUAL_FUND', 'BOND', 'NPS', 'EPF', 'FOREIGN_EQUITY',
                                             'CASH', 'GOLD', 'REIT')),
    quantity      NUMERIC(19,6) NOT NULL CHECK (quantity >= 0),
    avg_cost      NUMERIC(19,6) CHECK (avg_cost >= 0),
    currency      CHAR(3)       NOT NULL DEFAULT 'INR',
    account_id    UUID          REFERENCES financial_account (id) ON DELETE SET NULL,
    source        TEXT          NOT NULL CHECK (source IN ('AA', 'MANUAL', 'DERIVED', 'GMAIL')),
    opened_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    closed_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_portfolio_holding_closed CHECK (closed_at IS NULL OR quantity = 0)
);

CREATE INDEX idx_portfolio_holding_user_open ON portfolio_holding (user_id) WHERE closed_at IS NULL;
CREATE INDEX idx_portfolio_holding_user_asset_class ON portfolio_holding (user_id, asset_class);
CREATE INDEX idx_portfolio_holding_instrument ON portfolio_holding (instrument_id);
CREATE INDEX idx_portfolio_holding_account ON portfolio_holding (account_id) WHERE account_id IS NOT NULL;

-- Adjusts quantity/avg_cost on affected holdings deterministically; applied_at marks it as processed.
CREATE TABLE corporate_action (
    id            UUID          PRIMARY KEY,
    instrument_id UUID          NOT NULL REFERENCES instrument (id) ON DELETE RESTRICT,
    type          TEXT          NOT NULL CHECK (type IN ('SPLIT', 'BONUS', 'DIVIDEND', 'MERGER')),
    ex_date       DATE          NOT NULL,
    ratio         NUMERIC(19,6) CHECK (ratio > 0),
    applied_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_corporate_action UNIQUE (instrument_id, type, ex_date)
);

CREATE INDEX idx_corporate_action_pending ON corporate_action (ex_date) WHERE applied_at IS NULL;

-- Append-only time series; no id/created_at/updated_at.
CREATE TABLE price_quote (
    instrument_id UUID          NOT NULL REFERENCES instrument (id) ON DELETE RESTRICT,
    as_of         TIMESTAMPTZ   NOT NULL,
    price         NUMERIC(19,6) NOT NULL CHECK (price >= 0),
    source        TEXT          NOT NULL CHECK (source IN ('AA', 'MANUAL', 'DERIVED', 'GMAIL')),
    PRIMARY KEY (instrument_id, as_of)
);

SELECT create_hypertable('price_quote', 'as_of', chunk_time_interval => INTERVAL '7 days');

CREATE TABLE valuation_snapshot (
    user_id         UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    as_of           TIMESTAMPTZ   NOT NULL,
    by_asset_class  JSONB,
    total_value     NUMERIC(19,2),
    invested_value  NUMERIC(19,2),
    unrealised_gain NUMERIC(19,2),
    xirr            NUMERIC(9,4),
    PRIMARY KEY (user_id, as_of)
);

SELECT create_hypertable('valuation_snapshot', 'as_of', chunk_time_interval => INTERVAL '30 days');
