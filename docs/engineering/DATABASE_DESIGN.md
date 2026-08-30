# DATABASE_DESIGN.md — nyvra-backend

PostgreSQL 16 + TimescaleDB. One database, schema-per-concern optional; tables listed here by
bounded context (see `DOMAIN_MODEL.md`). This doc is the reference the Flyway migrations implement.

---

## Global conventions

| Topic | Rule |
|---|---|
| Primary keys | `UUID` (v7, time-ordered) — column `id`, `DEFAULT` generated in app, not DB |
| Foreign keys | Always declared; `ON DELETE RESTRICT` by default. Cascades only within an aggregate |
| Money columns | `NUMERIC(19,2)` for amounts, `NUMERIC(19,6)` for unit prices / NAV / FX / quantity. Never `float8` |
| Currency | Every money-bearing row has a `currency CHAR(3) NOT NULL DEFAULT 'INR'` |
| Timestamps | `created_at`, `updated_at` `TIMESTAMPTZ NOT NULL DEFAULT now()`; event times stored UTC |
| Accounting dates | `DATE` (no time) for `booking_date`, `value_date`, `period_start`, etc. |
| Soft delete | `deleted_at TIMESTAMPTZ NULL`; partial indexes filter `WHERE deleted_at IS NULL` |
| Tenancy | Every user-owned table has `user_id UUID NOT NULL REFERENCES user_profile(id)`; index leads with `user_id` |
| Enums | Postgres `TEXT` + `CHECK (col IN (...))`, not native `ENUM` (easier to evolve via migration) |
| Naming | `snake_case`; tables singular (`transaction`, not `transactions`) |
| Audit | `source TEXT` (`AA|MANUAL|DERIVED|GMAIL`) and `as_of TIMESTAMPTZ` on anything user-visible |
| Dedup | Ingested rows carry `dedup_key TEXT` with a `UNIQUE` constraint |

### Migration rules (Flyway)

- Location `src/main/resources/db/migration`, file `V<n>__<snake_case_description>.sql`.
- **Never edit an applied migration.** Fix-forward with a new one.
- One logical change per migration. Schema change and its backfill can share a file if the backfill is bounded.
- Every migration must be runnable on an empty DB and on a populated one.
- TimescaleDB `create_hypertable` and `add_continuous_aggregate_policy` calls live in migrations too.
- Destructive changes (`DROP COLUMN`, type narrowing) require a two-step deploy: stop writing → migrate → drop, across two releases.
- Seed data (system categories, instrument reference) goes in `V<n>__seed_*.sql`, idempotent (`INSERT ... ON CONFLICT DO NOTHING`).

---

## Field-level encryption

Columns marked 🔒 are encrypted at the application layer (AES-GCM, key from the environment's secret
store — see `ENVIRONMENTS.md`) before insert, decrypted on read. They are stored as `BYTEA`.
They must **not** be used in `WHERE`/`ORDER BY`; provide a separate hashed/blind-index column if lookup is needed.

| Column | Table | Why |
|---|---|---|
| `raw_json` 🔒 | `raw_financial_record` | Full AA payload — most sensitive data in the system |
| `parsed_fields` 🔒 | `payslip_document` | Salary breakup |
| `narration` 🔒 | `transaction` | Free-text can contain names, references |
| `counterparty` 🔒 | `transaction` | PII |
| `masked_number` 🔒 | `financial_account` | Even masked, treat as sensitive |
| `email` 🔒 | `user_profile` | PII; blind-index `email_hash` for lookup |

Never stored at all: full card PAN, CVV, bank login credentials, AA/broker access tokens beyond their
short TTL (those live in Redis with expiry, never in Postgres).

---

## Tables by context

### User

**`user_profile`**
| col | type | notes |
|---|---|---|
| id | uuid PK | |
| keycloak_subject | text UNIQUE NOT NULL | from OIDC `sub` |
| email 🔒 | bytea NOT NULL | |
| email_hash | text UNIQUE NOT NULL | blind index (HMAC-SHA256) |
| display_name | text | |
| date_of_birth | date | drives age-based rules |
| base_currency | char(3) NOT NULL DEFAULT 'INR' | immutable after first snapshot |
| dpdp_consent_at | timestamptz | |
| created_at / updated_at | timestamptz | |

**`user_preferences`** — `user_id` PK/FK (1:1), `risk_band TEXT CHECK (...)`, `emergency_fund_months_target NUMERIC(4,1)`, `dashboard_layout JSONB`, `notification_channels JSONB`.

**`data_consent_record`** — append-only. `id`, `user_id`, `purpose`, `scope JSONB`, `source TEXT`, `granted_at`, `expires_at`, `revoked_at`. No `updated_at` (immutable); revocation is a non-null `revoked_at` set once.
Index: `(user_id, source, granted_at DESC)`.

### Ingestion

**`aggregator_consent`** — `id`, `user_id`, `aa_handle`, `fip_list JSONB`, `consent_handle`, `consent_id UNIQUE`, `status TEXT CHECK (status IN ('REQUESTED','ACTIVE','PAUSED','REVOKED','EXPIRED'))`, `data_range_from date`, `data_range_to date`, `frequency TEXT`, `expires_at timestamptz`, timestamps.
Index: `(user_id, status)`, `(expires_at) WHERE status = 'ACTIVE'`.

**`fetch_session`** — `id`, `consent_id FK`, `session_id`, `fi_types JSONB`, `status TEXT`, `requested_at`, `completed_at`, `error TEXT`, `dedup_key TEXT UNIQUE` (`hash(consent_id, data_range_from, data_range_to)`).
Index: `(consent_id, requested_at DESC)`, `(status) WHERE status IN ('PENDING','RUNNING')`.

**`raw_financial_record`** — `id`, `fetch_session_id FK`, `fip_id`, `account_ref` (masked), `payload_type TEXT`, `raw_json 🔒 bytea`, `received_at`, `purge_after date NOT NULL`.
Index: `(fetch_session_id)`, `(purge_after)` for the retention job.
Retention: hard-deleted by a scheduled job once `purge_after < today` AND normalisation succeeded.

**`normalisation_run`** — `id`, `fetch_session_id FK`, `produced_events int`, `unmapped JSONB`, `status TEXT`, `created_at`.

### Accounts

**`financial_account`**
| col | type | notes |
|---|---|---|
| id | uuid PK | |
| user_id | uuid FK | |
| type | text CHECK (SAVINGS/CURRENT/LOAN/CREDIT_CARD/TERM_DEPOSIT/RECURRING_DEPOSIT/EPF/NPS) | |
| institution | text | |
| masked_number 🔒 | bytea | |
| label | text | user-given |
| currency | char(3) | |
| current_balance | numeric(19,2) | |
| balance_as_of | timestamptz NOT NULL | |
| source | text | AA/MANUAL |
| status | text CHECK (ACTIVE/CLOSED/STALE) | |
| deleted_at | timestamptz | |

Index: `(user_id, status) WHERE deleted_at IS NULL`, `(user_id, type)`.

**`transaction`** — partitioned by `RANGE (value_date)` monthly (native declarative partitioning).
| col | type | notes |
|---|---|---|
| id | uuid | PK is `(id, value_date)` for partitioning |
| account_id | uuid FK | |
| user_id | uuid | denormalised for query locality |
| booking_date / value_date | date | |
| amount | numeric(19,2) | signed |
| direction | text CHECK (DEBIT/CREDIT) | |
| narration 🔒 | bytea | |
| counterparty 🔒 | bytea | |
| balance_after | numeric(19,2) | |
| source | text | |
| dedup_key | text | UNIQUE within partition parent via unique index `(dedup_key, value_date)` |

Index: `(user_id, value_date DESC)`, `(account_id, value_date DESC)`.
Retention: partitions older than 10 years detached to cold storage (policy in `COMPLIANCE.md`).

**`card_detail`** — `id`, `financial_account_id FK`, `last4 char(4)`, `network text`, `label text`, `credit_limit numeric(19,2)`. No PAN/CVV/expiry-day. `CHECK (last4 ~ '^[0-9]{4}$')`.

### Income

**`income_source`** — `id`, `user_id`, `name`, `type text CHECK (...)`, `cadence text CHECK (...)`, `expected_amount numeric(19,2)`, `currency`, `active bool`, timestamps.
`CHECK (cadence = 'IRREGULAR' OR expected_amount IS NOT NULL)`.

**`income_entry`** — `id`, `source_id FK`, `user_id`, `period_start date`, `period_end date`, `gross_amount numeric(19,2)`, `net_amount numeric(19,2)`, `received_on date`, `linked_transaction_id uuid`, `origin text`.
`CHECK (net_amount <= gross_amount)`. Exclusion constraint: no overlapping `[period_start, period_end]` per `source_id` (`EXCLUDE USING gist`).
Index: `(user_id, period_start DESC)`.

**`payslip_document`** — `id`, `income_entry_id FK`, `object_key text`, `parsed_fields 🔒 bytea`, `uploaded_at`.

### Expenses

**`category`** — `id`, `name`, `parent_id uuid NULL FK self`, `necessity_default text`, `system bool NOT NULL`.
Unique `(parent_id, name)`. Seeded system tree in a `V__seed_categories.sql`.

**`categorisation_rule`** — `id`, `user_id uuid NULL` (null = system rule), `matcher_type text` (`MERCHANT_REGEX|NARRATION_REGEX|MCC`), `matcher_value text`, `category_id FK`, `necessity text`, `priority int NOT NULL`.
Index: `(user_id, priority DESC)`, `(matcher_type)`.

**`expense`** — partitioned by `RANGE (date)` monthly.
| col | type | notes |
|---|---|---|
| id | uuid | PK `(id, date)` |
| user_id | uuid | |
| transaction_id | uuid NULL | null when manually entered |
| parent_expense_id | uuid NULL | for splits |
| date | date | |
| amount | numeric(19,2) | |
| currency | char(3) | |
| category_id | uuid FK | |
| subcategory_id | uuid NULL FK | |
| merchant | text | |
| necessity | text CHECK (ESSENTIAL/DISCRETIONARY/DEBT_REPAYMENT/SAVINGS_TRANSFER) | |
| origin | text CHECK (AA/MANUAL/SPLIT) | |
| excluded_from_habits | bool NOT NULL DEFAULT false | |

Index: `(user_id, date DESC)`, `(user_id, category_id, date DESC)`, `(parent_expense_id)`.
Split invariant enforced in the service layer + a nightly reconciliation check.

**`spending_habit_snapshot`** — `id`, `user_id`, `period_month date` (first of month), `by_category_pct JSONB`, `essential_pct numeric(5,2)`, `discretionary_pct numeric(5,2)`, `total_spend numeric(19,2)`, `computed_at`. Unique `(user_id, period_month)`.

### Portfolio

**`instrument`** — `id`, `isin char(12) NULL UNIQUE`, `symbol text`, `name text`, `asset_class text CHECK (...)`, `currency char(3)`, `country char(2)`. Reference data, not user-owned.

**`portfolio_holding`** — `id`, `user_id`, `instrument_id FK`, `asset_class text`, `quantity numeric(19,6) CHECK (quantity >= 0)`, `avg_cost numeric(19,6)`, `currency char(3)`, `account_id uuid NULL FK`, `source text`, `opened_at`, `closed_at NULL`, timestamps.
Index: `(user_id) WHERE closed_at IS NULL`, `(user_id, asset_class)`.

**`corporate_action`** — `id`, `instrument_id FK`, `type text CHECK (SPLIT/BONUS/DIVIDEND/MERGER)`, `ex_date date`, `ratio numeric(19,6)`, `applied_at NULL`.

**`price_quote`** — **hypertable** (`SELECT create_hypertable('price_quote','as_of')`), chunk 7 days.
`instrument_id uuid`, `as_of timestamptz`, `price numeric(19,6)`, `source text`. PK `(instrument_id, as_of)`.
Continuous aggregate `price_quote_daily` (last price per instrument per day) with refresh policy.

**`valuation_snapshot`** — **hypertable** on `as_of`, chunk 30 days.
`user_id`, `as_of timestamptz`, `by_asset_class jsonb`, `total_value numeric(19,2)`, `invested_value numeric(19,2)`, `unrealised_gain numeric(19,2)`, `xirr numeric(9,4)`. PK `(user_id, as_of)`.

### Net Worth

**`net_worth_snapshot`** — **hypertable** on `as_of`, chunk 30 days.
`user_id`, `as_of timestamptz`, `total_assets numeric(19,2)`, `total_liabilities numeric(19,2)`, `net_worth numeric(19,2)`, `breakdown jsonb`, `contributing_sources jsonb`. PK `(user_id, as_of)`.
`CHECK (net_worth = total_assets - total_liabilities)`.
Continuous aggregate `net_worth_monthly` for the long trend view.

**`manual_asset_liability`** — `id`, `user_id`, `kind text CHECK (ASSET/LIABILITY)`, `class text CHECK (...)`, `label text`, `value numeric(19,2)`, `value_as_of date NOT NULL`, `revaluation_cadence interval`, `deleted_at`. Index `(user_id) WHERE deleted_at IS NULL`.

### Analytics + Health Score

**`health_score`** — **hypertable** on `as_of`, chunk 90 days.
`user_id`, `as_of timestamptz`, `overall numeric(5,2) CHECK (overall BETWEEN 0 AND 100)`, `band text CHECK (NEEDS_ATTENTION/FAIR/GOOD/EXCELLENT)`, `sub_scores jsonb NOT NULL`, `confidence numeric(4,3)`, `inputs_hash text`. PK `(user_id, as_of)`.
`inputs_hash` lets the engine skip recompute when nothing changed.

**`insight`** — `id`, `user_id`, `rule_id text`, `severity text CHECK (INFO/WARN/CRITICAL)`, `message text`, `evidence jsonb`, `raised_at`, `dismissed_at NULL`, `superseded_at NULL`.
Index: `(user_id, raised_at DESC) WHERE dismissed_at IS NULL AND superseded_at IS NULL`.

**`dashboard_summary_cache`** — optional Postgres fallback for the Redis-cached summary: `user_id PK`, `payload jsonb`, `computed_at`. Redis is primary; this is the durable rebuild source.

---

## Indexing & performance notes

- Every list endpoint is `(user_id, <sort_date> DESC)` — those composite indexes are mandatory, not optional.
- `transaction` and `expense` are the only high-volume tables → declarative monthly partitioning from day one.
- Timescale hypertables: `price_quote`, `valuation_snapshot`, `net_worth_snapshot`, `health_score`.
- Continuous aggregates back every trend chart — never aggregate raw rows on the request path.
- JSONB breakdown columns are for display payloads only; anything filtered/aggregated gets a real column.
- Connection pool (HikariCP) sized per environment in `ENVIRONMENTS.md`.

## Backup & DR (summarised; full detail in `OBSERVABILITY.md` / infra docs)

- `prod`: PITR (WAL archiving) + nightly base backup, 35-day retention, cross-AZ. Restore drills quarterly.
- `staging`: nightly logical dump, 7-day retention.
- `dev` / `local`: no backup expectation; reproducible from migrations + seed.
- Encryption keys are backed up **separately** from data; losing the key = losing all 🔒 columns.
