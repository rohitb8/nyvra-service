# schema.md — how to read `schema.dbml`

`schema.dbml` is the **intended target schema** — the single file that describes what the database
should look like, applied or not. Open it visually at [dbdiagram.io](https://dbdiagram.io) (paste the
file contents, or use its GitHub-import if you host this repo there) to browse or edit it as a diagram.

## What's applied vs proposed

Every table/column carries a `Note` saying which migration introduces it and whether that migration
has actually been applied yet. Right now:

- **Applied (V1):** `user_profile` (minus `email`/`email_hash`/`date_of_birth`/`dpdp_consent_at` —
  those are proposed), `user_preferences`, `data_consent_record`.
- **Proposed, not yet built:** everything else — the V2/V3 email-encryption retrofit, and V4 through
  V10 (Accounts → Income → Expenses → Portfolio → Net Worth → Analytics → Ingestion).

The actual, ground-truth migration history is `database/migrations/` — a symlink to
`src/main/resources/db/migration/`, the real folder Flyway executes against Postgres. `schema.dbml`
can describe a future state that folder hasn't caught up to yet; the migrations folder never lies
about what's actually been applied.

## Companion files

| File | For |
|---|---|
| `schema.dbml` | The schema itself — tables, columns, relationships. Edit this to propose a change. |
| `schema.md` (this file) | How to read `schema.dbml` and how the pieces fit together. |
| `decisions.md` | Why the schema looks the way it does — the standing conventions every table follows. |
| `migrations/` | Symlink → the real Flyway migration history (applied + pending). |

For the full prose column/index/constraint spec (the level of detail DBML doesn't carry well —
exact `CHECK` clauses, partition strategy, hypertable chunk intervals), see
[`docs/engineering/DATABASE_DESIGN.md`](../docs/engineering/DATABASE_DESIGN.md). `schema.dbml` is the
visual/structural view; `DATABASE_DESIGN.md` is the detailed prose spec. Keep both in sync when either
changes.

## Conventions baked into every table (see `decisions.md` for the *why*)

- Primary keys: `UUID` v7 (time-ordered), generated in the app.
- Money columns: `NUMERIC(19,2)` for amounts, `NUMERIC(19,6)` for prices/NAV/FX/quantity — paired with
  a `CHAR(3)` currency column, mapped to a single `Money` value type on the Java side.
- 🔒-marked columns are encrypted (AES-256-GCM) at the application layer, stored as `bytea` — **from
  the table's first migration**, never retrofitted later, except `user_profile.email` (the one table
  that already existed before this convention was adopted).
- Enums in this file (`Enum risk_band { ... }` etc.) are DBML documentation sugar for the diagram —
  the real Postgres columns are `TEXT` + `CHECK (col IN (...))`, never native `ENUM` (see
  `DATABASE_DESIGN.md`'s "Global conventions" table for why).
- Every user-owned table's indexes lead with `user_id`.
- `transaction` and `expense` are declaratively partitioned by month — their diagrammed primary key is
  the composite `(id, date-column)`, not a bare `id`.
- `price_quote`, `valuation_snapshot`, `net_worth_snapshot`, `health_score` are TimescaleDB hypertables
  from creation.

## Changing the schema

1. Edit `schema.dbml` (by hand, or visually in dbdiagram.io and paste the export back).
2. Run the `/db-sync` skill (`.claude/skills/db-sync/`). It reads `schema.dbml` as the target, compares
   it against the real migration history + current JPA entities (never assumes the entities are
   already correct), and generates the migration + entity/repository/DTO/test changes needed to close
   the gap — flagging anything destructive (a drop or rename) for your explicit confirmation before
   writing it, rather than doing it silently.
3. Review the generated migration + code changes like any other PR.
