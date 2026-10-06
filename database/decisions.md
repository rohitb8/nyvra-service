# decisions.md — architectural decisions behind `schema.dbml`

The decisions below were made during design review, and every table in `schema.dbml` follows them. The first four came first; that every table in `schema.dbml` follows. Each was a real
fork in the road — recorded here (not just applied silently) so a later change to any of them is a
deliberate revision of a decision, not an accidental drift.

---

## 1. Field-level encryption: built in from each table's first migration

**Decision:** every 🔒 column is `bytea`-encrypted from the migration that creates its table. No new
table is ever created with a plaintext sensitive column, even temporarily.

**Why:** the "usual" pattern — create a table in plaintext, retrofit encryption later via a two-step
expand/contract migration — exists specifically to protect *already-live* production data during a
zero-downtime change. Every table this decision applies to (`financial_account`, `transaction`,
`payslip_document`, `raw_financial_record`, …) is **brand new** — there's no existing data to protect,
so the expand/contract dance would be pure overhead with no safety benefit.

**The one exception:** `user_profile.email` already exists (applied in V1) with real rows. That table
*does* get the genuine two-step treatment — see the `V2`/`V3` migrations. It's the one place this
decision doesn't apply, precisely because it's the one table the general argument above doesn't hold
for.

**Consequence:** the crypto utility (`EncryptedStringConverter` — AES-256-GCM, dual-key via
`NYVRA_FIELD_ENCRYPTION_KEY`/`_PREVIOUS`; `BlindIndexHasher` — HMAC-SHA256 via
`NYVRA_BLIND_INDEX_KEY`) has to exist *before* any table that needs it, i.e. early, not as a
Phase-2-ending cleanup task.

---

## 2. Partition management: an app-level scheduled job, not `pg_partman`

**Decision:** `transaction` and `expense` (the only high-volume tables) are declaratively
range-partitioned by month. A new Spring `@Scheduled` + ShedLock job
(`common/partition/MonthlyRangePartitionMaintenance`) keeps partitions a few months ahead by issuing
plain `CREATE TABLE ... PARTITION OF ...` DDL — not the `pg_partman` Postgres extension.

**Why:** `pg_partman` is purpose-built and battle-tested for exactly this, but it's a second Postgres
extension whose availability isn't guaranteed on every managed Postgres offering — and a cloud
provider hasn't been chosen yet (`docs/operations/ENVIRONMENTS.md` §7 is still a fill-in-later table).
Stacking a second extension-availability risk on top of TimescaleDB's already-flagged one works
against the project's explicit "cloud-agnostic for now" stance. An app-level job is plain SQL DDL —
works identically anywhere Postgres runs, and reuses the same ShedLock mechanism the daily
net-worth/health-score snapshot jobs already need.

**Safety net:** both partitioned tables get a `DEFAULT` partition, so an insert never fails outright
if the maintenance job has fallen behind — it lands in the default partition instead, which should
stay empty in practice and is a signal worth alerting on if it isn't (Phase 7.3 concern).

**History imports:** an AA fetch can bring years of past transactions, far behind the job's
"current month + N ahead" window. Writers of historical data call `MonthlyPartitions.ensureRange`
for the months they're about to write first. Either way, the SQL helper `ensure_monthly_partition()`
(created in V2) moves any rows already sitting in `DEFAULT` for that month into the new partition before
attaching it — a plain `CREATE TABLE ... PARTITION OF` would otherwise fail on them.

---

## 3. TimescaleDB hypertables: from creation, not converted later

**Decision:** `price_quote`, `valuation_snapshot`, `net_worth_snapshot`, and `health_score` become
hypertables (`create_hypertable(...)`) in the same migration that creates them — not created as plain
tables first and converted via a separate later migration.

**Why:** the extension is already known-available in every environment this project currently
targets — local Docker Compose, CI, and Testcontainers all run `timescale/timescaledb-ha:pg16`
already. There's no "prove the extension is available first" reason to defer, and skipping the
conversion step removes a whole migration plus the `migrate_data => true` complexity that only matters
for converting a table that already has rows.

---

## 4. Money representation: a shared `Money` value type

**Decision:** every money value is a `Money(BigDecimal amount, String currency)` on the Java side
(`common/money/Money`), not separate `BigDecimal`/`String` pairs passed around per entity.

**As built (V2–V4):** `Money` is a plain value record, not a JPA `@Embeddable`. Each row keeps one
`currency` column and one `NUMERIC` column per amount; the entity's constructor takes `Money`, and its
getters return `Money`. An embeddable broke down on rows with several amounts sharing one currency
(`income_entry` gross + net, `transaction` amount + balance after) and on nullable amounts
(`income_source.expected_amount`), where Hibernate would rebuild a half-null `Money`. `Money` rejects
more than 2 decimals rather than rounding — rounding is a `FINANCIAL_RULES` decision the caller makes.

**Why:** the underlying SQL is identical either way (`NUMERIC` + `CHAR(3)`) — this is purely a
Java-layer decision. A shared type centralizes scale/rounding rules and currency-mismatch safety once
instead of re-deriving them in every entity, which matters a great deal once the `FINANCIAL_RULES`/
health-score engine (Phase 4) is doing arithmetic across dozens of money fields. Retrofitting this
after dozens of entities and DTOs already exist would cost far more than adopting it now, before any
entity beyond `UserProfile` exists.

---

## 5. Migration numbers are assigned when written, never reserved

**Decision:** a Flyway version number is given to a migration only when it's actually written. Earlier
drafts reserved V2/V3 for the `user_profile.email` retrofit and V4–V10 for the contexts; Accounts,
Income and Expenses shipped as **V2, V3, V4** (+ `V4.1__seed_categories.sql`) instead.

**Why:** Flyway applies versions in order and, with `outOfOrder` off, refuses to apply V2 once V4 is
in an environment. Reserving gaps would have made the email retrofit unshippable.

---

## 6. No foreign keys across modules into partitioned tables

**Decision:** `expense.transaction_id` and `income_entry.linked_transaction_id` are plain indexed UUIDs
with no foreign key to `transaction`. FKs to `user_profile` (the tenancy key) remain.

**Why:** two reasons, either sufficient. `transaction`'s primary key is `(id, value_date)` (partitioned
tables must include the partition key), so an id-only FK isn't possible. And `ARCHITECTURE.md` rule 6
says no module reaches into another's tables — a cross-module FK is exactly that coupling.
Within a module the rule doesn't apply: split expenses reference their parent through
`(parent_expense_id, date)`, so a split always shares its parent's date.

---

## 7. Custom categories belong to a user

**Decision:** `category.user_id` (nullable; `NULL` = system category) was added during review.
`CHECK (system = (user_id IS NULL))`, `CHECK (system OR parent_id IS NOT NULL)` (users only add children),
and `UNIQUE NULLS NOT DISTINCT (user_id, parent_id, name)`.

**Why:** without an owner, one user's custom category would be visible to everyone.

---

## Open — continuous aggregates deferred

Not a decision so much as a scheduling note: `DATABASE_DESIGN.md` calls for continuous aggregates
(`price_quote_daily`, `net_worth_monthly`, trend rollups) backing every chart. These are proposed to
land alongside the code that first *writes* to each hypertable (the net-worth service, the
health-score engine, the price feed) rather than as part of this schema pass — defining an aggregate
over an empty hypertable is unverifiable. Flagged here so it isn't silently lost between phases.
