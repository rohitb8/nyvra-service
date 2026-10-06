-- V3 — Income context: income_source, income_entry, payslip_document.
-- Target: database/schema.dbml (Income). Conventions: database/decisions.md.

-- Needed for the per-source no-overlap exclusion constraint on income_entry (UUID equality in GiST).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE income_source (
    id              UUID          PRIMARY KEY,
    user_id         UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    name            TEXT          NOT NULL,
    type            TEXT          NOT NULL
                        CHECK (type IN ('SALARY', 'BUSINESS', 'RENTAL', 'INTEREST', 'DIVIDEND',
                                        'CAPITAL_GAIN', 'OTHER')),
    cadence         TEXT          NOT NULL CHECK (cadence IN ('MONTHLY', 'QUARTERLY', 'ANNUAL', 'IRREGULAR')),
    expected_amount NUMERIC(19,2),
    currency        CHAR(3)       NOT NULL DEFAULT 'INR',
    active          BOOLEAN       NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_income_source_expected_amount CHECK (cadence = 'IRREGULAR' OR expected_amount IS NOT NULL)
);

CREATE INDEX idx_income_source_user ON income_source (user_id, active);

-- linked_transaction_id points into the Accounts module's partitioned transaction table, so it is a
-- plain id without a foreign key (ARCHITECTURE.md rule 6; a FK would also need value_date).
CREATE TABLE income_entry (
    id                    UUID          PRIMARY KEY,
    source_id             UUID          NOT NULL REFERENCES income_source (id) ON DELETE RESTRICT,
    user_id               UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    period_start          DATE          NOT NULL,
    period_end            DATE          NOT NULL,
    gross_amount          NUMERIC(19,2) NOT NULL,
    net_amount            NUMERIC(19,2) NOT NULL,
    currency              CHAR(3)       NOT NULL DEFAULT 'INR',
    received_on           DATE          NOT NULL,
    linked_transaction_id UUID,
    origin                TEXT          NOT NULL CHECK (origin IN ('AA_DETECTED', 'MANUAL', 'PAYSLIP')),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_income_entry_period CHECK (period_end >= period_start),
    CONSTRAINT chk_income_entry_net_le_gross CHECK (net_amount <= gross_amount),
    CONSTRAINT ex_income_entry_no_overlap EXCLUDE USING gist (
        source_id WITH =,
        daterange(period_start, period_end, '[]') WITH &&)
);

CREATE INDEX idx_income_entry_user_period ON income_entry (user_id, period_start DESC);
CREATE INDEX idx_income_entry_linked_transaction
    ON income_entry (linked_transaction_id) WHERE linked_transaction_id IS NOT NULL;

-- The document body lives in object storage (MinIO/S3); only its key and the 🔒 parsed fields are here.
CREATE TABLE payslip_document (
    id              UUID        PRIMARY KEY,
    income_entry_id UUID        NOT NULL REFERENCES income_entry (id) ON DELETE CASCADE,
    object_key      TEXT        NOT NULL,
    parsed_fields   BYTEA,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payslip_document_entry ON payslip_document (income_entry_id);
