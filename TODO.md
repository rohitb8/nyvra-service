# nyvra-service — TODO (detailed roadmap)

Work top to bottom. Phases are ordered; sub-tasks within a phase can mostly go in parallel.
`[x]` done · `[~]` partial · `[ ]` not started. Each task lists **what**, **why/notes**, the design
doc to consult, and **done when**.

> **Cross-repo critical path:** Phase 3 (a frozen `openapi/nyvra-api-v1.yaml`) unblocks `nyvra-ui`
> screen work. `nyvra-ui` design work (its Phase 1) can run fully in parallel starting now.

Legend for doc refs: `PO`=PROJECT_OVERVIEW, `TS`=TECH_STACK, `DM`=DOMAIN_MODEL, `DB`=DATABASE_DESIGN,
`FR`=FINANCIAL_RULES, `HS`=HEALTH_SCORE_SPEC, `ENV`=ENVIRONMENTS, `STR`=BACKEND_STRUCTURE
(`PO`/`DM`/`FR`/`HS` in `docs/product/`, `TS`/`DB`/`STR` in `docs/engineering/`, `ENV` in
`docs/operations/` — see `docs/CLAUDE.md`).

---

## Phase 0 — Skeleton  ✅ done

- [x] Spring Boot 3.3 + Spring Modulith app, package `com.rohit.nyvra`, `/api/v1` prefix
- [x] OAuth2 resource-server security (Keycloak JWT), realm-role → `ROLE_*` mapping, CORS, method security
- [x] `user` module: `UserProfile` + JIT provisioning (`CurrentUserService`) + `GET /api/v1/users/me`
- [x] Flyway `V1` (user_profile, user_preferences, data_consent_record)
- [x] `common/exception`: `ApiError` + `GlobalExceptionHandler`
- [x] Profiles `local/dev/staging/prod` + `test`; all env values via env vars; `.env.example`
- [x] docker-compose (Postgres/Timescale, Redis, RabbitMQ, MinIO, Keycloak + its DB) + realm import
- [x] Layered `Dockerfile`, Maven wrapper, springdoc, `ArchitectureTest`
- [x] Design docs: PO, TS, DM, DB, FR, HS, ENV + updated STR + root `CLAUDE.md`

---

## Phase 1 — Foundation & environments

### 1.1 Verify the local stack end-to-end
- [x] `docker compose up -d` (via `start-local-server.sh`, cold Docker-daemon-down start); wait for all healthchecks green — fixed `--wait` not handling the one-shot `minio-setup` container, and the Keycloak admin bootstrap using `KC_BOOTSTRAP_ADMIN_*` (Keycloak 26+ only) against the pinned 25.0 image
- [x] Keycloak: log in at `:8081` (admin/admin), confirm realm `nyvra`, clients `nyvra-web` + `nyvra-api`, users `demo`/`admin` present and can authenticate
- [x] **Add a way to mint a local token**: `directAccessGrantsEnabled` enabled on `nyvra-web` (local only); `curl` password-grant one-liner documented in `README.md`
- [x] `./mvnw spring-boot:run` (profile `local`) → Flyway `V1` applies, `/actuator/health` = UP — fixed a schema/entity mismatch on `user_profile.base_currency` (`CHAR` vs Hibernate's default `VARCHAR`) that failed `ddl-auto=validate` on every boot
- [x] `GET /api/v1/users/me` with `Authorization: Bearer <token>` → 200 and a row appears in `user_profile`
- [x] Swagger UI `Authorize` (PKCE) works against local Keycloak — fixed `nyvra-web`'s `redirectUris`, which only allowed the Angular SPA's origin, not Swagger UI's own OAuth2 callback (`localhost:8080/swagger-ui/oauth2-redirect.html`), causing Keycloak's "Invalid parameter: redirect_uri"
- **Done when:** a "Local smoke test" section in `README.md` passes from a clean `down -v`. ✅ — README section written, curl flow and Swagger UI's Authorize flow both verified end to end. A literal `down -v` full reset re-run is the one remaining nice-to-have (not blocking; the individual pieces — cold Docker-daemon-down start, fresh-schema Flyway migration — have each been proven separately this session).

### 1.2 CI pipeline
- [x] `.github/workflows/ci.yml`: trigger on PR + push to `main`
- [x] Steps: checkout → `actions/setup-java@v5` (temurin 21, maven cache) → `./mvnw -B verify`
- [x] Flyway validation against an ephemeral DB — decided on a `postgres` service (`timescale/timescaledb-ha:pg16`, matching prod); added `flyway-maven-plugin` to `pom.xml` (wasn't there before, despite `ENV` §9 documenting `./mvnw flyway:migrate`) so the check is a real `flyway:migrate` run against an empty DB, not just app-boot side-effect
- [~] **OpenAPI drift check**: deliberately a guarded placeholder for now, not the full boot+diff — `openapi/nyvra-api-v1.yaml` doesn't exist yet (Phase 3.5), and a real boot-and-diff needs a live/mocked OIDC issuer for `SecurityConfig`'s `JwtDecoder` to resolve, which is Phase 1.3's `mock-oauth2-server`. Revisit once both land.
- [x] Build the Docker image on `main` (don't push yet)
- [ ] Enable branch protection on `main` requiring CI green — CI is green on GitHub, so this is unblocked, but **deliberately deferred**: required status checks block direct pushes to `main` (a commit needs an existing passing check run before it can land, not just at PR-merge time), which conflicts with the direct-to-main workflow for initial setup. Revisit once the project switches to feature-branch + PR (see the git-workflow session memory).
- **Done when:** a test PR shows a green required check.  Ref: `ENV` §8.

### 1.3 Test infrastructure
- [x] `AbstractIntegrationTest` base: `@SpringBootTest` + `@Testcontainers` + `@ServiceConnection` on a **`timescale/timescaledb-ha:pg16`** Postgres container (must match prod so hypertable DDL runs), `@ActiveProfiles("test")` — needed bumping `testcontainers.version` to `1.21.4` (Spring Boot 3.3.5's managed `1.19.8` can't talk to newer Docker Desktop/Engine releases)
- [x] Mock the OIDC decoder: went with MockMvc `.with(jwt())` postprocessors (`TestSecurityConfig`'s stub `JwtDecoder` lets the context boot without a reachable issuer) rather than `mock-oauth2-server` — simpler, no extra CI container; revisit if Phase 3.4's real audience/issuer validation needs a live-token test
- [x] Test data builders / object-mothers per aggregate — `UserProfileMother` (only aggregate so far)
- [x] One real repository integration test that runs in CI — `UserProfileRepositoryIntegrationTest`; also added `UserControllerIntegrationTest` to prove the OIDC-mock path end to end
- **Done when:** `./mvnw verify` runs a Testcontainers test locally and in CI.  Ref: `TS` (Tests row).

### 1.4 `ARCHITECTURE.md`  ✅ done
- [x] System context + container diagram (app, Postgres/Timescale, Redis, RabbitMQ, object store, Keycloak, AA provider, price feed)
- [x] Module map + **allowed dependency directions** (mirror `DM` context map); which crossings are events vs direct module-API calls
- [x] Sync path (dashboard reads) vs async path (ingestion pipeline)
- [x] ADR index (the 3 locked decisions from `PO` §4 + auth = Keycloak + this session's testing/CI decisions) — full one-per-file ADRs not set up, index table only, per TODO's own phrasing
- [x] `ArchitectureTest` enforcement noted as a Phase 2+ follow-up (blocked on modules having real classes to annotate)
- **Done when:** committed to `docs/engineering/` and linked from `docs/CLAUDE.md`.

### 1.5 Logging & error baseline  ✅ done
- [x] `logback-spring.xml` with profile guards: pretty console on `local` **and `test`** (readability for a human debugging beats literal "JSON on the rest" — see the doc's own comment), JSON (`logstash-logback-encoder`) on `dev`/`staging`/`prod`
- [x] Correlation/trace id in every log line — went with a plain per-request `CorrelationIdFilter` (MDC `traceId`, echoed as `X-Request-Id`) rather than full Micrometer Tracing, which is still `TS`'s "Planned" row, not "Now"; `userId` set post-auth via a second filter, from the JWT **subject**, never `email`
- [x] `GlobalExceptionHandler` 400/404/500 unchanged; **fixed** 401/403 — they never actually reached the handler before this (Spring Security's filter-chain defaults intercepted them ahead of MVC dispatch), now `ApiErrorAuthenticationEntryPoint`/`ApiErrorAccessDeniedHandler` cover them; added 409 (`ConflictException` + `DataIntegrityViolationException`, the latter already reachable today), 422 (`UnprocessableEntityException`), 429 (`RateLimitExceededException`) — all `ApiError`-shaped with `traceId`, no leaked exception messages on 500
- [x] Asserted via two focused unit tests rather than one log-capture integration test: `UserIdMdcFilterTest` (subject in MDC, email/name never do) and `GlobalExceptionHandlerTest` (500 never echoes the original message)

### 1.6 Secrets strategy sign-off  ✅ done
- [x] Documented generation of `NYVRA_FIELD_ENCRYPTION_KEY` and `NYVRA_BLIND_INDEX_KEY` (`openssl rand -base64 32`) — `ENV` §6.2, cross-linked from `docs/PREREQUISITES.md`; also fixed `NYVRA_BLIND_INDEX_KEY` being entirely missing from `ENV` §6's inventory table and from `.env.example` despite being referenced elsewhere
- [x] Confirmed `.gitignore` actually blocks `.env*` (verified with `git check-ignore`, not just read the file) while keeping `.env.example` tracked; added a gitleaks CI step (`.github/workflows/ci.yml`, full-history scan) — caught and fixed one real finding (a false positive on `docker-compose.yml`'s local-only Keycloak DB URL, now allowlisted via `.gitleaks.toml` with a documented reason)
- [x] Provider abstraction decision written up explicitly — `ENV` §6.1 (env vars now, swap the injection mechanism for Vault/cloud secret manager later with zero app code change, since the app only ever reads env vars)
- [x] Key-rotation runbook stub — `ENV` §6.3: dual-key zero-downtime procedure for the field-encryption key, plus an explicit callout that the blind-index key does **not** rotate the same way (deterministic HMAC — needs a recompute pass, not dual-key tolerance)

---

## Phase 2 — Database design finalized

**The schema itself is no longer designed inline in this file.** `database/schema.dbml` is the
authoritative target schema (editable visually at dbdiagram.io); `database/decisions.md` records the
four conventions decisions below with full rationale; `database/schema.md` explains how the pieces fit
together. This section only tracks *status* — build order and done-when criteria — not column lists.
Once `schema.dbml` is signed off, run the `/db-sync` skill (`.claude/skills/db-sync/`) to generate the
actual migrations + entities; it diffs `schema.dbml` against the real migration history and current
JPA entities and never assumes the entities are already right.

**Status: Accounts, Income, Expenses built** (V2–V4.1). Portfolio, Net Worth, Analytics, Ingestion and
the email retrofit are still proposed in `database/schema.dbml`.

### 2.1 Conventions — decided and implemented
Recorded in `database/decisions.md` (rationale) — implementation is a `/db-sync` output once schema
work starts, not a standalone task:
- [x] UUID v7 (`com.github.f4b6a3:uuid-creator`), generated in the app for every `@Id` — `common/persistence/AbstractEntity`.
- [x] `AbstractEntity` `@MappedSuperclass` (`id`, `createdAt`, `updatedAt`, `Persistable`) — `UserProfile` retrofitted to extend it.
- [x] A shared `Money` value type on every money-bearing field — built as a record, not an `@Embeddable` (`decisions.md` §4 "As built").
- [x] Partition management for `transaction`/`expense` via `@Scheduled` + ShedLock (`common/partition/`), not `pg_partman` — `decisions.md` §2.
- [x] Crypto utility (`common/crypto/`: `FieldEncryptor` AES-256-GCM dual-key, `EncryptedStringConverter`, `BlindIndexHasher`) — keys required at startup.
- [x] **Decided:** `price_quote`/`valuation_snapshot`/`net_worth_snapshot`/`health_score` become TimescaleDB hypertables in their own creation migration, not a separate later conversion — `decisions.md` §3.
- [x] **Decided:** every new (not-yet-applied) table encrypts its 🔒 columns from creation; only `user_profile.email` (already applied in V1) gets a genuine two-step retrofit — `decisions.md` §1.
- [x] Enums: `@Enumerated(STRING)` + a DB `CHECK` in the migration (not native PG enum).
- [ ] `@Transactional(readOnly = true)` default on read services.

### 2.2 Migration build order
Per `database/schema.dbml`'s notes on which migration each table belongs to. One migration per
context, in dependency order (mirrors `docs/engineering/ARCHITECTURE.md`'s module map):

Version numbers are assigned when a migration is written, never reserved ahead — Flyway won't apply a
lower version after a higher one (`decisions.md` §5).

- [x] `V2` — Accounts (`financial_account`, `transaction`, `card_detail`) + `shedlock` + `ensure_monthly_partition()` — test persists an account + 1000 transactions spanning 2 monthly partitions and pages them (`AccountsRepositoryIntegrationTest`)
- [x] `V3` — Income (`income_source`, `income_entry`, `payslip_document`) — `btree_gist` for the no-overlap exclusion constraint
- [x] `V4` — Expenses (`category`, `categorisation_rule`, `expense`, `spending_habit_snapshot`) + `V4.1__seed_categories.sql`
- [ ] Retrofit `user_profile.email` to encrypted, two-step (the one exception to "encrypt from creation", since it already has real rows) — crypto utility now exists
- [ ] Portfolio (`instrument`, `portfolio_holding`, `corporate_action`, `price_quote`, `valuation_snapshot`) — first hypertables; `CREATE EXTENSION IF NOT EXISTS timescaledb` here
- [ ] Net Worth (`net_worth_snapshot`, `manual_asset_liability`)
- [ ] Analytics (`health_score`, `insight`, `dashboard_summary_cache`)
- [ ] Ingestion (`aggregator_consent`, `fetch_session`, `raw_financial_record`, `normalisation_run`) + the `raw_financial_record` retention job

For every migration: JPA entity, Spring Data repository, and a repository slice test extending
`AbstractIntegrationTest`. Keep `ddl-auto=validate` — Flyway owns the schema.

### 2.3 Deferred out of this phase (tracked in `database/decisions.md`'s "Open" note)
- [ ] Continuous aggregates (`price_quote_daily`, `net_worth_monthly`, trend rollups) — land alongside
  the code that first *writes* to each hypertable (Phase 4/5), not as part of the schema pass.
- [ ] `add_retention_policy` on raw `price_quote` history, once there's enough of it to matter.

### 2.4 Query patterns
- [ ] Every list query leads with `(user_id, <sort_date> DESC)` + `Pageable`; add the composite indexes
- [ ] No N+1 (entity graphs / fetch joins); assert with a query-count test on hot paths
- [ ] Filterable lists via Spring Data `Specification` (decide vs Querydsl)

---

## Phase 3 — API contract  ← unblocks `nyvra-ui` screens

### 3.1 `API_DESIGN.md`
- [ ] Resource naming, plural nouns, nesting (`/accounts/{id}/transactions`)
- [ ] **Pagination**: `?page=&size=` + envelope `{content,page,size,totalElements,totalPages}`; **cursor** (`?cursor=&limit=`) for `transaction`/`expense` (high volume) — decide, document both if mixed
- [ ] Sorting `?sort=field,dir`; per-resource filter params
- [ ] Error envelope = `ApiError`; enumerate codes 400/401/403/404/409/422/429/500 with example bodies
- [ ] **Idempotency**: `Idempotency-Key` header on creating POSTs; store key + response hash in Redis with TTL
- [ ] **Money in JSON**: string, not number (avoid float); currency alongside — document
- [ ] Dates: `LocalDate` → `yyyy-MM-dd`; `Instant` → UTC `…Z`; server UTC, client renders `Asia/Kolkata`
- [ ] Versioning + deprecation-header policy (breaking → `/api/v2`)

### 3.2 DTOs + mappers
- [ ] Request/response **records** per module in `<module>/dto/`; MapStruct mappers in `<module>/mapper/`
- [ ] Bean Validation on request DTOs (`@NotNull`, `@Positive`, `@Size`, custom `@ValidCurrency`, `@ValidDateRange`)
- [ ] `PageResponse<T>` wrapper; never expose/accept JPA entities (`STR` → dto/)

### 3.3 Controllers per module (all under `/api/v1`)
- [ ] `AccountController` — list/get/create-manual/update-label/close; `TransactionController` (list paged, get)
- [ ] `IncomeController` — sources CRUD, entries CRUD, `POST /income/entries/{id}/payslip` (multipart → MinIO)
- [ ] `ExpenseController` — list, get, update category/necessity, split, rules CRUD; `GET /spending/habits?month=`
- [ ] `PortfolioController` — holdings list/get, manual holding CRUD, `GET /portfolio/allocation`, `GET /portfolio/xirr`
- [ ] `NetWorthController` — current, `GET /net-worth/history?from=&to=&granularity=`, manual asset/liability CRUD
- [ ] `AnalyticsController` — `dashboard-summary`, `trends`, `insights` (list, `POST {id}/dismiss`), `health-score` (current, history)
- [ ] `UserController` — `me` (done), update profile, preferences, `GET /users/me/consents`, `POST /users/me/data-export`, `POST /users/me/deletion-request`
- [ ] `GoalController` — CRUD + progress
- [ ] `AggregatorController` — `POST /aggregator/consents` (start), consent callback, `GET /aggregator/accounts`, `POST /aggregator/refresh`, `POST /aggregator/consents/{id}/revoke`

### 3.4 Auth & authorization
- [ ] `@PreAuthorize("hasRole('USER')")` default; admin endpoints `hasRole('ADMIN')`
- [ ] **Ownership** on every user-scoped query: filter by `currentUserService.currentUser().getId()`; explicit check on get-by-id (return 404 not 403 for another user's resource)
- [ ] **Add JWT audience + expiry validators** to `SecurityConfig` (currently issuer-only): `JwtValidators.createDefaultWithIssuer` + a custom audience validator on `NYVRA_OIDC_AUDIENCE`
- [ ] Stub a per-user rate-limit interceptor seam (impl in Phase 7)

### 3.5 OpenAPI export & freeze
- [ ] springdoc annotations on every controller + DTO (`@Operation`, `@Schema`, examples, error responses)
- [ ] Export: `curl -s localhost:8080/v3/api-docs.yaml > src/main/resources/openapi/nyvra-api-v1.yaml`; **commit it**
- [ ] CI regenerates + diffs (fails on drift) — from 1.2
- [ ] Tag `api-v1.0` once `nyvra-ui` starts consuming; announce so it runs `generate:api`
- **Done when:** `nyvra-api-v1.yaml` is committed, CI drift check is green, and the frontend can generate a client from it offline.

---

## Phase 4 — Core business logic (the product's brain)

### 4.1 `FINANCIAL_RULES` engine  (`FR`)
- [ ] `financial-rules.yml` in resources; `@ConfigurationProperties(prefix="financial-rules")` record tree incl. `ruleset_version`; per-env override support
- [ ] `RuleContext` assembler: trailing-window (3/6/12mo) income, essential/discretionary spend, liquid assets, monthly essential burn, debt payments, holdings + allocation, `age` (from DOB), `riskBand`
- [ ] Trailing-window helper util, tested with a **fixed `Clock`**
- [ ] One class per rule group implementing `FinancialRule` → `RuleResult(value, status, evaluated, insight?)`:
  SavingsRate, ExpenseRatio, FixedObligations, EmergencyFund, Debt (DTI, CC-util, high-interest), Allocation (glide path, single-stock/sector/AMC/employer concentration, fund overlap, cash drag), NetWorthTrend
- [ ] Data-sufficiency gates (`FR` §6) → `evaluated=false` (don't drag score)
- [ ] Insight precedence ordering (`FR` §7); rounding (full precision, `HALF_UP` at display only)
- [ ] Edge cases: `net_income=0` → skip; negative savings → clamp `[-1,0]`; division-by-zero → skip
- [ ] Stamp `ruleset_version` on outputs
- **Done when:** table-driven unit tests cover every rule at its boundary values from `FR`.

### 4.2 `HEALTH_SCORE` engine  (`HS`)
- [ ] `HealthScoreCalculator` with `ramp(x,lo,hi)` / `invramp` helpers
- [ ] Sub-scores: savings, spendingControl (2-component avg + lifestyle-creep −10), emergencyFund, debt (penalty accumulation, no-debt = 100), diversification (penalty accumulation), goalProgress (not evaluated if no goals)
- [ ] Base weights + **proportional redistribution** across evaluated sub-scores; `<3 evaluated → band INSUFFICIENT_DATA`, `overall=null`
- [ ] Confidence = `0.4·evaluatedWeightShare + 0.4·freshnessFactor + 0.2·historyDepthFactor`
- [ ] Hard caps: emergency < min → 45; high-interest debt / fixed-obligations-over-ceiling → 55; negative savings → 40; **lowest applicable cap wins**; cap reason added to `topDrivers`
- [ ] `topDrivers` = top-3 evaluated sub-scores by `weight·(100−value)`
- [ ] `inputsHash = sha256(canonicalJson(driverMetrics + ruleset_version + SCORE_SPEC_VERSION))`; skip write if unchanged
- [ ] Persist to `health_score` hypertable; recompute on the events in `HS` §5 (debounced 60s/user) + a daily job
- **Done when:** tests reproduce the `HS` §6 worked example (**96.8 / EXCELLENT**), each hard cap, and `INSUFFICIENT_DATA`.

### 4.3 Net-worth service  (`DM` → Net Worth)
- [ ] Aggregate account balances (assets vs liabilities by type) + portfolio valuation + manual assets/liabilities
- [ ] `NetWorthSnapshotService.recompute(userId)` — debounced on `AccountBalanceChanged` / `LiabilityBalanceChanged` / `PortfolioValued` / manual edits
- [ ] Daily guaranteed snapshot `@Scheduled` + **ShedLock** (leader-only) so the trend has no gaps
- [ ] Upsert one snapshot per `(userId, date)`; `breakdown` jsonb by class

### 4.4 Spending categorisation  (`DM` → Expenses)
- [ ] On `TransactionRecorded`: apply `CategorisationRule` (user rules first, then system, by `priority`; MCC / merchant regex / narration regex) → create `Expense` with category + necessity
- [ ] Manual recategorise endpoint → option to persist a user rule ("always categorise X as Y")
- [ ] Split expense: children sum == parent (service check + nightly reconciliation)
- [ ] `SpendingHabitSnapshot` recompute per affected month; percentages sum 100 ± rounding; exclude `SAVINGS_TRANSFER` + `excluded_from_habits`

### 4.5 Portfolio valuation  (`DM` → Portfolio, `FR` §4)
- [ ] `PortfolioValuationService`: latest `PriceQuote` × quantity, by asset class; invested vs current; unrealised gain
- [ ] **XIRR** (Newton–Raphson with bisection fallback) over cashflows — **test against known XIRR values**
- [ ] Corporate actions: apply split/bonus to `quantity` + `avg_cost` deterministically
- [ ] Target allocation from glide path + risk band (`FR` §4.1); compare; emit `AllocationDrifted` on ±5 / CRITICAL on ±10
- [ ] Write `ValuationSnapshot` (daily + on holdings change)

### 4.6 Goals  (`HS` §3.6)
- [ ] `Goal` entity (target amount, target date, weight, linked source/manual contributions)
- [ ] `required_run_rate_to_date` straight-line; `per_goal_progress = clamp(current/required, 0, 1)`
- [ ] Feed `goalProgress` sub-score; no goals → not evaluated

### 4.7 Insights  (`DM` → Analytics)
- [ ] Generate from rule breaches; map severity; `evidence` jsonb carries the numbers
- [ ] Dedupe: no duplicate active insight per `ruleId`; supersede on change; dismiss endpoint
- [ ] Dashboard "top 3" via precedence (`FR` §7)

---

## Phase 5 — Ingestion pipeline (RBI Account Aggregator)

### 5.1 Docs
- [ ] `INTEGRATIONS.md` — per external (AA provider, price/NAV feed, Gmail): base URLs, auth (mTLS/signing), rate limits, retry/backoff, pagination, sandbox vs prod, data contracts, error taxonomy
- [ ] `DATA_INGESTION.md` — consent → fetch → normalise → categorise → dedup; FI-type→domain mapping tables; dedup-key formulas; reconciliation rules
- [ ] `EVENT_DESIGN.md` — exchanges, queues, routing keys, versioned message schemas, DLQ, retry policy, ordering guarantees, idempotency, poison-message handling

### 5.2 RabbitMQ topology
- [ ] `@Configuration` declaring topic exchange `nyvra.ingestion`, per-consumer queues, DLX + DLQ, retry/TTL queues (or Spring Retry), manual ack, prefetch/concurrency
- [ ] Message classes with a `schemaVersion` field; Jackson (de)serialisation contract tests
- [ ] Metrics: queue depth, consumer lag; alert thresholds

### 5.3 Aggregator module — consent  (`PO` §4.2, `DM` → Ingestion)
- [ ] Sign up for a **sandbox** AA provider (Setu / Finvu / OneMoney); store sandbox creds in `.env`
- [ ] `AggregatorClient` interface + one impl; provider DTOs isolated inside the module (nothing downstream sees AA SDK types)
- [ ] Consent request: build (FI types, purpose code, data range, frequency, expiry) → provider → consent handle + user-approval redirect URL
- [ ] Consent callback/webhook: **verify signature**, move `AggregatorConsent` `REQUESTED→ACTIVE`, store consent id/artefact, also write a `DataConsentRecord` (DPDP)
- [ ] State machine: `ACTIVE↔PAUSED`, `REVOKED`, `EXPIRED`; scheduled expiry check
- [ ] Revoke endpoint → provider + local state + stop fetches

### 5.4 Aggregator module — FI data fetch
- [ ] `FetchSession` per active consent + data range; idempotent per `(consentId, dataRange)`
- [ ] Poll or webhook for readiness; fetch encrypted FI data; persist `RawFinancialRecord` (encrypted, `purge_after` set)
- [ ] Resilience4j retry + circuit breaker; per-provider rate limiter (bucket4j/Redis or R4j `RateLimiter`)
- [ ] Record `FetchSession` status/error

### 5.5 Normalisation
- [ ] `NormalisationRun` per fetch session; **deterministic + re-runnable** (dedup keys prevent double-count)
- [ ] Map raw FI JSON per `payload_type` → events, each with a stable `dedupKey`:
  DEPOSIT/CARD → `AccountDiscovered` / `BalanceUpdated` / `TransactionsIngested`; MUTUAL_FUND/EQUITIES/NPS/EPF → `HoldingsIngested`; LOAN/CREDIT_CARD → `LiabilityBalanceChanged`; salary credits → `IncomeCreditDetected`
- [ ] Unmapped fields → `normalisation_run.unmapped` for review

### 5.6 Downstream consumers
- [ ] Accounts: `AccountDiscovered` / `TransactionsIngested` / `BalanceUpdated` → **upsert on `dedupKey`**
- [ ] Portfolio: `HoldingsIngested` → upsert holdings; resolve/create `Instrument`
- [ ] Income: `IncomeCreditDetected` → propose/confirm `IncomeEntry`
- [ ] NetWorth + Analytics/HealthScore: subscribe → **debounced** recompute

### 5.7 Price / NAV feed
- [ ] Pick sources: AMFI NAV file (free, for MF); an equities provider (document choice + cost)
- [ ] Scheduled fetch → `PriceQuote` upsert (hypertable) → `price_quote_daily` continuous aggregate; cache latest in Redis
- [ ] Resilience + rate limiting

### 5.8 Gmail supplement (low priority, optional)  (`PO` §3, `PO` §4.2)
- [ ] OAuth consent (read-only scope); parse transactional emails → `EmailDerivedHint` (low trust)
- [ ] **Never** creates transactions — only proposes categorisation/merchant hints; record DPDP consent

### 5.9 Resilience & backpressure
- [ ] Resilience4j retry / circuitbreaker / ratelimiter / bulkhead / timelimiter per external call
- [ ] Bounded queues + consumer concurrency limits + DLQ alerts
- [ ] Idempotency everywhere (dedupKey upserts)
- [ ] Reconciliation job: detect gaps/mismatches → re-fetch

---

## Phase 6 — Analytics & dashboard endpoints  (`DM` → Analytics)

### 6.1 Dashboard summary
- [ ] `DashboardSummaryService` assembles: net worth + 30d Δ, monthly income/spend avg, savings rate, emergency-fund months, top categories, allocation vs target, recent insights, health score
- [ ] Redis cache (key per user, TTL + explicit eviction on recompute events); `dashboard_summary_cache` as the durable rebuild source
- [ ] `GET /api/v1/analytics/dashboard-summary`; surface insufficient-data state (what to connect)

### 6.2 Trends
- [ ] `GET /api/v1/analytics/trends?metric=&granularity=&from=&to=` backed by **continuous aggregates** (never aggregate raw rows on the request path)
- [ ] Metrics: net worth, spend, income, savings rate, score, allocation

### 6.3 Insights & score history
- [ ] `GET/POST /analytics/insights`, `POST /analytics/insights/{id}/dismiss`
- [ ] `GET /analytics/health-score` (current) + `?history` (trend line)

---

## Phase 7 — Hardening & prod readiness

### 7.1 `SECURITY.md` + implementation
- [ ] Threat model (STRIDE per module boundary)
- [ ] JWT: issuer + **audience** + expiry validators; clock-skew tolerance; reject `alg=none`
- [ ] **Audit log** (append-only table + shipped to sink): first `/me` touch, consent grant/revoke, data export, deletion request, admin actions
- [ ] Input hardening: max request/body size, max page size, multipart limits, **regex-DoS review** on categorisation rules
- [ ] Dependency scanning (OWASP dependency-check or Snyk) in CI; `gitleaks`
- [ ] Confirm no secret in logs / exception messages / actuator; `management` on separate port in staging/prod (done) + network policy

### 7.2 `COMPLIANCE.md` + implementation (DPDP Act 2023)  (`PO` §5)
- [ ] Consent: purpose-bound, granular, revocable records + a consent receipt
- [ ] Retention schedule per data type; purge jobs (raw records, detach old partitions to cold storage)
- [ ] **Right to erasure**: documented cross-context hard-delete workflow (order: analytics → derived → source → user), job + admin trigger + audit, legal-hold exceptions
- [ ] **Right to portability**: export job → JSON+CSV archive to object storage → signed URL with expiry
- [ ] Data-processing register (what / why / where / retention / sub-processors: AA provider, price feed, cloud)
- [ ] Breach-notification runbook; grievance-officer contact (India requirement)

### 7.3 `OBSERVABILITY.md` + implementation
- [ ] Structured JSON logs + correlation id (from 1.5); levels per env (done in yml)
- [ ] Metrics (Micrometer → Prometheus) + custom: ingestion lag, fetch success rate, score recompute time, rule-eval counts, external-call latency
- [ ] Tracing: Micrometer Tracing + OTLP exporter; propagate across RabbitMQ
- [ ] Sentry (errors); dashboards + **alert catalogue** with SLOs (API p99, ingestion success %, queue depth, DB connections, error rate)
- [ ] Dependency health indicators on `/actuator/health`

### 7.4 `TEST_STRATEGY.md` + suites
- [ ] Pyramid: unit (rules/score/XIRR heavy) · slice (`@DataJpaTest`, `@WebMvcTest`) · integration (Testcontainers full context) · module (`spring-modulith`) · contract (OpenAPI) · a few e2e
- [ ] Coverage targets (e.g. 85% overall, **100% on financial-calc packages**)
- [ ] Mock external HTTP with WireMock; **never mock the DB**
- [ ] Tighten `ArchitectureTest` to `modules.verify()` once boundaries + named interfaces exist
- [ ] Golden-file tests for financial calculations; PIT mutation testing on `analytics` + rules packages
- [ ] CI runs all; nightly runs slow/e2e

### 7.5 `CI_CD.md` + pipeline  (`ENV` §8)
- [ ] Stages: build → unit → integration → package image → scan → publish (tag = git sha + semver) → deploy dev (auto) → deploy staging (on tag) → **migrate prod (gated, backup-verified job)** → deploy prod (manual approve) → smoke
- [ ] Migrations run as a dedicated `flyway:migrate` job in staging/prod (yml already sets `flyway.enabled=false` at runtime there)
- [ ] Rollback = redeploy previous image tag; **expand/contract migrations** so rollback is always safe
- [ ] Config + secrets via platform; config changes go through PR review

### 7.6 Cloud provisioning  (`ENV` §7 — fill the table)
- [ ] Choose provider; complete the AWS/GCP/Azure mapping in `ENV` §7
- [ ] IaC (Terraform): container service, managed Postgres (+ Timescale — self-install or Timescale Cloud), managed Redis, RabbitMQ (or Amazon MQ), object-storage bucket + lifecycle rules, secret manager, load balancer, DNS, TLS, VPC/network policy
- [ ] **India region** (data residency for DPDP)
- [ ] Keycloak deployed per env (container + its own managed Postgres); realm import via CI
- [ ] Provision dev / staging / prod (sizes scaled)

### 7.7 DB operations  (`DB` → Backup & DR)
- [ ] prod: PITR (WAL archiving) + nightly base backup, 35-day retention, cross-AZ; staging: nightly logical dump, 7-day
- [ ] **Quarterly restore drill** (documented + timed)
- [ ] prod read replica; route read-only queries there
- [ ] Hikari pool sizing vs DB `max_connections`
- [ ] Encryption key backup **separate** from data backups

### 7.8 Performance
- [ ] Load-test the dashboard read path + ingestion throughput (k6 / Gatling)
- [ ] `EXPLAIN` the list queries — confirm the composite indexes are used
- [ ] Confirm every trend chart hits a continuous aggregate; tune cache TTLs / hit ratios
- [ ] Virtual threads on (done) — check for pinning (no `synchronized` around blocking I/O)

---

## Phase 8 — Launch

- [ ] Production Keycloak hardening: MFA/OTP, brute-force detection, password policy, session timeouts, email verification + SMTP, admin 2FA, realm export in CI
- [ ] Legal: privacy policy, terms, DPDP notices, consent copy — reviewed
- [ ] Data-residency attestation
- [ ] Runbooks: on-call, incident response, rollback, key rotation, DB restore
- [ ] Monitoring/alerting live + a synthetic alert fired to test the path
- [ ] Rate limits tuned for expected load
- [ ] Security review / pen test (`/security-review` or external)
- [ ] Go-live checklist sign-off
- [ ] Post-launch: watch error rate, ingestion success %, latency for 48h
