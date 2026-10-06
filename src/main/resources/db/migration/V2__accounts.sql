-- V2 — Accounts context: financial_account, transaction, card_detail.
-- Target: database/schema.dbml (Accounts). Conventions and their rationale: database/decisions.md.
--
--   * 🔒 columns (masked_number, narration, counterparty) are AES-GCM ciphertext (BYTEA) from creation,
--     encrypted by the app (EncryptedStringConverter) — decisions.md §1.
--   * transaction is RANGE-partitioned by value_date, monthly, with a DEFAULT partition — decisions.md §2.
--   * enums are TEXT + CHECK, never native ENUM.
--
-- Also lands two pieces of shared infrastructure, because this is the first migration that needs them:
-- ShedLock's lock table and the ensure_monthly_partition() helper.

-- ---------------------------------------------------------------------------------------------
-- Shared: ShedLock (cluster-safe @Scheduled jobs — SchedulingConfig)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

-- ---------------------------------------------------------------------------------------------
-- Shared: monthly range-partition helper (MonthlyPartitions / MonthlyRangePartitionMaintenance)
--
-- Idempotently creates <table>_YYYY_MM for the month containing month_start. If rows for that month
-- already sit in the DEFAULT partition (e.g. an AA history import ran before the partition existed),
-- they are moved into the new partition before it is attached — a plain
-- CREATE TABLE ... PARTITION OF would fail in that case.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION ensure_monthly_partition(parent REGCLASS, month_start DATE)
    RETURNS REGCLASS
    LANGUAGE plpgsql
AS $$
DECLARE
    from_date      DATE := date_trunc('month', month_start)::date;
    to_date        DATE := (date_trunc('month', month_start) + INTERVAL '1 month')::date;
    parent_schema  TEXT;
    parent_name    TEXT;
    partition_name TEXT;
    partition_key  TEXT;
    default_part   REGCLASS;
BEGIN
    SELECT n.nspname, c.relname INTO parent_schema, parent_name
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.oid = parent;

    partition_name := parent_name || '_' || to_char(from_date, 'YYYY_MM');

    -- Serialise concurrent callers (the maintenance job vs an ingestion write) for this partition.
    PERFORM pg_advisory_xact_lock(hashtext(parent_schema || '.' || partition_name));

    IF to_regclass(format('%I.%I', parent_schema, partition_name)) IS NOT NULL THEN
        RETURN to_regclass(format('%I.%I', parent_schema, partition_name));
    END IF;

    SELECT a.attname, NULLIF(pt.partdefid, 0)::regclass INTO partition_key, default_part
    FROM pg_partitioned_table pt
    JOIN pg_attribute a ON a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0]
    WHERE pt.partrelid = parent;

    IF partition_key IS NULL THEN
        RAISE EXCEPTION '% is not a range-partitioned table', parent;
    END IF;

    EXECUTE format('CREATE TABLE %I.%I (LIKE %s INCLUDING DEFAULTS INCLUDING CONSTRAINTS)',
                   parent_schema, partition_name, parent);

    IF default_part IS NOT NULL THEN
        EXECUTE format(
            'WITH moved AS (DELETE FROM %s WHERE %I >= $1 AND %I < $2 RETURNING *) '
                || 'INSERT INTO %I.%I SELECT * FROM moved',
            default_part, partition_key, partition_key, parent_schema, partition_name)
            USING from_date, to_date;
    END IF;

    EXECUTE format('ALTER TABLE %s ATTACH PARTITION %I.%I FOR VALUES FROM (%L) TO (%L)',
                   parent, parent_schema, partition_name, from_date, to_date);

    RETURN to_regclass(format('%I.%I', parent_schema, partition_name));
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- financial_account
-- Never stores a full account number or credentials — masked_number only (PROJECT_OVERVIEW §4.1).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE financial_account (
    id              UUID          PRIMARY KEY,
    user_id         UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    type            TEXT          NOT NULL
                        CHECK (type IN ('SAVINGS', 'CURRENT', 'LOAN', 'CREDIT_CARD', 'TERM_DEPOSIT',
                                        'RECURRING_DEPOSIT', 'EPF', 'NPS')),
    institution     TEXT,
    masked_number   BYTEA,
    label           TEXT,
    currency        CHAR(3)       NOT NULL DEFAULT 'INR',
    current_balance NUMERIC(19,2) NOT NULL,
    balance_as_of   TIMESTAMPTZ   NOT NULL,
    source          TEXT          NOT NULL CHECK (source IN ('AA', 'MANUAL', 'DERIVED', 'GMAIL')),
    status          TEXT          NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED', 'STALE')),
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_financial_account_user_status
    ON financial_account (user_id, status) WHERE deleted_at IS NULL;
CREATE INDEX idx_financial_account_user_type
    ON financial_account (user_id, type);

-- ---------------------------------------------------------------------------------------------
-- transaction — high volume, immutable once persisted (corrections are new reversing rows).
-- PK is (id, value_date) because a partitioned table's unique constraints must include the
-- partition key. Other modules reference a transaction by id only, without a foreign key
-- (ARCHITECTURE.md rule 6).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE transaction (
    id            UUID          NOT NULL,
    value_date    DATE          NOT NULL,
    account_id    UUID          NOT NULL REFERENCES financial_account (id) ON DELETE RESTRICT,
    user_id       UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    booking_date  DATE          NOT NULL,
    amount        NUMERIC(19,2) NOT NULL,
    currency      CHAR(3)       NOT NULL DEFAULT 'INR',
    direction     TEXT          NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    narration     BYTEA,
    counterparty  BYTEA,
    balance_after NUMERIC(19,2),
    source        TEXT          NOT NULL CHECK (source IN ('AA', 'MANUAL', 'DERIVED', 'GMAIL')),
    dedup_key     TEXT          NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id, value_date),
    -- amount is signed; direction must agree with the sign.
    CONSTRAINT chk_transaction_direction_sign CHECK (
        (direction = 'DEBIT' AND amount <= 0) OR (direction = 'CREDIT' AND amount >= 0))
) PARTITION BY RANGE (value_date);

-- dedup_key already hashes value_date in, so uniqueness per (dedup_key, value_date) is global.
CREATE UNIQUE INDEX uq_transaction_dedup ON transaction (dedup_key, value_date);
CREATE INDEX idx_transaction_user_date ON transaction (user_id, value_date DESC);
CREATE INDEX idx_transaction_account_date ON transaction (account_id, value_date DESC);

CREATE TABLE transaction_default PARTITION OF transaction DEFAULT;

SELECT ensure_monthly_partition('transaction', (date_trunc('month', now()) + make_interval(months => m))::date)
FROM generate_series(0, 3) AS m;

-- ---------------------------------------------------------------------------------------------
-- card_detail — last4 + network + label only. No PAN/CVV/expiry columns, ever (PROJECT_OVERVIEW §4.1).
-- Part of the financial_account aggregate, so it cascades with it.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE card_detail (
    id                   UUID          PRIMARY KEY,
    financial_account_id UUID          NOT NULL REFERENCES financial_account (id) ON DELETE CASCADE,
    last4                CHAR(4)       NOT NULL CHECK (last4 ~ '^[0-9]{4}$'),
    network              TEXT          NOT NULL CHECK (network IN ('VISA', 'MASTERCARD', 'RUPAY', 'AMEX')),
    label                TEXT,
    credit_limit         NUMERIC(19,2),
    currency             CHAR(3)       NOT NULL DEFAULT 'INR',
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_card_detail_account ON card_detail (financial_account_id);
