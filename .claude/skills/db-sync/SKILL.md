---
name: db-sync
description: Compare database/schema.dbml (the intended target schema) against the real migration history and current JPA entities, then generate the Flyway migration and Java changes needed to close the gap. Use when database/schema.dbml has been edited and the app needs to catch up, or when asked to "sync the schema" / "apply the schema changes" / "generate the migration for schema.dbml".
---

# db-sync

Brings the application in line with `database/schema.dbml`, the intended target schema. `schema.dbml`
is a human-edited design file (by hand, or visually via dbdiagram.io) — this skill never writes to it,
only reads it as the target to build toward.

## Database Development Rules

- Treat `database/schema.dbml` as the intended target schema.
- Compare it with the current application implementation — never assume the existing JPA entity model
  is already correct. It may be stale relative to `schema.dbml`, or `schema.dbml` may describe a state
  nothing has been built for yet.
- Generate/update database migrations for schema changes.
- Update JPA entities when applicable.
- Update repositories and queries when applicable.
- Update DTOs/API contracts when schema changes affect them.
- Update tests.
- Preserve existing data when designing migrations, unless explicitly instructed otherwise.
- Never drop or rename a column without explicitly calling out the data-migration implications and
  getting confirmation first — do not silently generate a destructive migration.

## Procedure

1. **Read the target.** Load `database/schema.dbml` in full — every table, column, type, note,
   relationship. Notes marking a table/column "proposed, not yet built" vs "applied" are load-bearing;
   don't skip them.

2. **Reconstruct current reality.** Read every file in `src/main/resources/db/migration/` (via
   `database/migrations/`, its symlink — same files) in version order to know what's actually been
   applied. Separately, read the current JPA entities (`@Entity` classes under
   `src/main/java/com/rohit/nyvra/`) to know what the Java layer currently claims the schema looks
   like. These two can disagree with each other, not just with `schema.dbml` — surface that if found,
   don't silently trust either.

3. **Diff** target vs. current-applied-schema, table by table, column by column:
   - New table → additive, safe.
   - New nullable column, new index, new non-conflicting constraint → additive, safe.
   - Column type change, `NOT NULL` tightening on a populated column, `DROP COLUMN`, a rename → **not
     safe by default**. Stop and describe: what data is affected, what the two-step expand/contract
     migration would look like (see `database/decisions.md` §1 for the pattern this project already
     uses), and get explicit confirmation before writing that specific migration. A rename is a
     drop+add in disguise — treat it the same way.

4. **For each safe, confirmed change, generate:**
   - A new Flyway migration in `src/main/resources/db/migration/`
     (`V<n>__<snake_case_description>.sql`, `<n>` = next unused version). Never edit an applied
     migration — fix-forward only. Apply the same conventions already established: 🔒 columns
     encrypted from creation (not retrofitted) for any genuinely new table; hypertables/partitioning
     from creation where `schema.dbml`'s notes call for it; `TEXT + CHECK` for enums, not native `ENUM`.
   - The JPA entity (new or updated) — `AbstractEntity` base, `Money` embeddable for amount+currency
     pairs, `@Enumerated(STRING)`, matching `docs/engineering/CODE_STYLE.md`'s conventions
     (constructor injection elsewhere doesn't apply here, but the explicit-getters/no-Lombok-on-entities
     rule does).
   - The Spring Data repository (new or updated query methods) — `findByUserIdOrderBy...(Pageable)` /
     `findByIdAndUserId` ownership-scoped patterns, matching `UserProfileRepository`.
   - DTOs/mappers/controllers, only if the change actually affects the API contract — don't touch
     these for a purely internal column.
   - Tests: a repository slice test extending `AbstractIntegrationTest` (see
     `UserProfileRepositoryIntegrationTest` for the shape) proving the new/changed column round-trips;
     update any existing test whose fixtures the change affects.

5. **Verify.** Run `./mvnw -B -ntp verify`. It must pass — Testcontainers Postgres applies every
   migration in order as part of that run, so a broken migration or a bad entity mapping fails loudly
   here, not later.

6. **Keep the docs honest.** If the change alters what `database/schema.md` or
   `database/decisions.md` describe (a new convention, a changed rationale), update them in the same
   pass — same standard as the rest of this project's doc-staleness discipline. `database/migrations/`
   needs no separate update; it's a symlink to the real folder, so step 4's migration file already
   shows up there.

7. **Report what happened**, table by table: what was generated, what was flagged as destructive and
   skipped pending confirmation, and the `mvnw verify` result. Don't silently succeed past a skipped
   destructive change.

## What this skill does not do

- Never modifies `database/schema.dbml` itself — that file is the human-authored target; this skill
  only ever reads it.
- Never applies a destructive change (drop/rename/narrowing) without explicit confirmation on that
  specific change, even if the rest of the sync is otherwise safe to apply automatically.
- Never touches an already-applied migration file — fixes are always a new migration, forward-only.
