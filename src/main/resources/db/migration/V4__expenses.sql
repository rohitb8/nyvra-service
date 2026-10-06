-- V4 — Expenses context: category, categorisation_rule, expense, spending_habit_snapshot.
-- Target: database/schema.dbml (Expenses). Conventions: database/decisions.md.
-- The system category tree is seeded separately in V4.1__seed_categories.sql.

-- System categories have user_id NULL and are immutable; users add custom children under them.
-- NULLS NOT DISTINCT so two root categories (parent_id NULL) can't share a name either.
CREATE TABLE category (
    id                UUID        PRIMARY KEY,
    user_id           UUID        REFERENCES user_profile (id) ON DELETE RESTRICT,
    name              TEXT        NOT NULL,
    parent_id         UUID        REFERENCES category (id) ON DELETE RESTRICT,
    necessity_default TEXT        CHECK (necessity_default IN ('ESSENTIAL', 'DISCRETIONARY',
                                                               'DEBT_REPAYMENT', 'SAVINGS_TRANSFER')),
    system            BOOLEAN     NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_owner_parent_name UNIQUE NULLS NOT DISTINCT (user_id, parent_id, name),
    CONSTRAINT chk_category_system_owner CHECK (system = (user_id IS NULL)),
    CONSTRAINT chk_category_custom_is_child CHECK (system OR parent_id IS NOT NULL)
);

CREATE INDEX idx_category_user ON category (user_id);
CREATE INDEX idx_category_parent ON category (parent_id);

-- user_id NULL = system rule. Higher priority wins; user rules override system rules.
CREATE TABLE categorisation_rule (
    id            UUID        PRIMARY KEY,
    user_id       UUID        REFERENCES user_profile (id) ON DELETE RESTRICT,
    matcher_type  TEXT        NOT NULL CHECK (matcher_type IN ('MERCHANT_REGEX', 'NARRATION_REGEX', 'MCC')),
    matcher_value TEXT        NOT NULL,
    category_id   UUID        NOT NULL REFERENCES category (id) ON DELETE RESTRICT,
    necessity     TEXT        NOT NULL
                      CHECK (necessity IN ('ESSENTIAL', 'DISCRETIONARY', 'DEBT_REPAYMENT', 'SAVINGS_TRANSFER')),
    priority      INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_categorisation_rule_user_priority ON categorisation_rule (user_id, priority DESC);
CREATE INDEX idx_categorisation_rule_matcher_type ON categorisation_rule (matcher_type);

-- expense — high volume, RANGE-partitioned by date, monthly, + DEFAULT (decisions.md §2).
-- transaction_id points into the Accounts module: a plain id without a foreign key (ARCHITECTURE.md
-- rule 6). Split children reference their parent by (parent_expense_id, date): a split always shares its
-- parent's date. The children-sum-to-parent invariant is enforced in the service layer + nightly
-- reconciliation, not here.
CREATE TABLE expense (
    id                   UUID          NOT NULL,
    date                 DATE          NOT NULL,
    user_id              UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    transaction_id       UUID,
    parent_expense_id    UUID,
    amount               NUMERIC(19,2) NOT NULL,
    currency             CHAR(3)       NOT NULL DEFAULT 'INR',
    category_id          UUID          NOT NULL REFERENCES category (id) ON DELETE RESTRICT,
    subcategory_id       UUID          REFERENCES category (id) ON DELETE RESTRICT,
    merchant             TEXT,
    necessity            TEXT          NOT NULL
                             CHECK (necessity IN ('ESSENTIAL', 'DISCRETIONARY', 'DEBT_REPAYMENT',
                                                  'SAVINGS_TRANSFER')),
    origin               TEXT          NOT NULL CHECK (origin IN ('AA', 'MANUAL', 'SPLIT')),
    excluded_from_habits BOOLEAN       NOT NULL DEFAULT false,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id, date)
) PARTITION BY RANGE (date);

ALTER TABLE expense
    ADD CONSTRAINT fk_expense_parent FOREIGN KEY (parent_expense_id, date)
        REFERENCES expense (id, date) ON DELETE CASCADE;

CREATE INDEX idx_expense_user_date ON expense (user_id, date DESC);
CREATE INDEX idx_expense_user_category_date ON expense (user_id, category_id, date DESC);
CREATE INDEX idx_expense_parent ON expense (parent_expense_id) WHERE parent_expense_id IS NOT NULL;
CREATE INDEX idx_expense_transaction ON expense (transaction_id) WHERE transaction_id IS NOT NULL;

CREATE TABLE expense_default PARTITION OF expense DEFAULT;

SELECT ensure_monthly_partition('expense', (date_trunc('month', now()) + make_interval(months => m))::date)
FROM generate_series(0, 3) AS m;

-- Recomputed on any expense change in the period.
CREATE TABLE spending_habit_snapshot (
    id                UUID          PRIMARY KEY,
    user_id           UUID          NOT NULL REFERENCES user_profile (id) ON DELETE RESTRICT,
    period_month      DATE          NOT NULL CHECK (period_month = date_trunc('month', period_month)::date),
    by_category_pct   JSONB,
    essential_pct     NUMERIC(5,2)  CHECK (essential_pct BETWEEN 0 AND 100),
    discretionary_pct NUMERIC(5,2)  CHECK (discretionary_pct BETWEEN 0 AND 100),
    total_spend       NUMERIC(19,2),
    currency          CHAR(3)       NOT NULL DEFAULT 'INR',
    computed_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_spending_habit_snapshot_user_month UNIQUE (user_id, period_month)
);
