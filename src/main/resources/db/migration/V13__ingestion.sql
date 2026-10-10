-- V13 — Ingestion context: aggregator_consent, fetch_session, raw_financial_record, normalisation_run.
-- Target: database/schema.dbml (Ingestion). Conventions: database/decisions.md.
-- Schema only: no entities, services or endpoints yet. Ingestion feeds other contexts through
-- published events, never foreign keys (ARCHITECTURE.md module rules).

-- State machine: REQUESTED -> ACTIVE -> (PAUSED <-> ACTIVE) -> REVOKED | EXPIRED. No fetch unless ACTIVE.
CREATE TABLE aggregator_consent (
    id              UUID        PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    aa_handle       TEXT        NOT NULL,
    fip_list        JSONB,
    consent_handle  TEXT,
    consent_id      TEXT        UNIQUE,
    status          TEXT        NOT NULL
                        CHECK (status IN ('REQUESTED', 'ACTIVE', 'PAUSED', 'REVOKED', 'EXPIRED')),
    data_range_from DATE,
    data_range_to   DATE,
    frequency       TEXT,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_aggregator_consent_range CHECK (data_range_to IS NULL OR data_range_from IS NULL
                                                   OR data_range_to >= data_range_from)
);

CREATE INDEX idx_aggregator_consent_user_status ON aggregator_consent (user_id, status);
CREATE INDEX idx_aggregator_consent_expires_active ON aggregator_consent (expires_at) WHERE status = 'ACTIVE';

-- Idempotent per (consent, data range) via dedup_key = hash(consent_id, data_range_from, data_range_to).
CREATE TABLE fetch_session (
    id           UUID        PRIMARY KEY,
    consent_id   UUID        NOT NULL REFERENCES aggregator_consent (id) ON DELETE RESTRICT,
    session_id   TEXT,
    fi_types     JSONB,
    status       TEXT        NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    requested_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error        TEXT,
    dedup_key    TEXT        UNIQUE
);

CREATE INDEX idx_fetch_session_consent_requested ON fetch_session (consent_id, requested_at);

-- raw_json is 🔒 AES-GCM ciphertext (BYTEA) from creation — the most sensitive table in the system.
-- Hard-deleted by a scheduled job once purge_after < today AND normalisation succeeded.
CREATE TABLE raw_financial_record (
    id               UUID        PRIMARY KEY,
    fetch_session_id UUID        NOT NULL REFERENCES fetch_session (id) ON DELETE RESTRICT,
    fip_id           TEXT,
    account_ref      TEXT,
    payload_type     TEXT        NOT NULL
                         CHECK (payload_type IN ('DEPOSIT', 'TERM_DEPOSIT', 'RECURRING_DEPOSIT', 'MUTUAL_FUND',
                                                 'EQUITIES', 'NPS', 'EPF', 'LOAN', 'CREDIT_CARD')),
    raw_json         BYTEA,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    purge_after      DATE        NOT NULL
);

CREATE INDEX idx_raw_financial_record_session ON raw_financial_record (fetch_session_id);
CREATE INDEX idx_raw_financial_record_purge ON raw_financial_record (purge_after);

-- Deterministic and re-runnable: downstream dedup keys prevent double-counting.
CREATE TABLE normalisation_run (
    id               UUID        PRIMARY KEY,
    fetch_session_id UUID        NOT NULL REFERENCES fetch_session (id) ON DELETE RESTRICT,
    produced_events  INTEGER     CHECK (produced_events >= 0),
    unmapped         JSONB,
    status           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_normalisation_run_session ON normalisation_run (fetch_session_id);
