# nyvra-service — TODO (priority order)

High-level roadmap to get the backend production-ready. Work top to bottom; phases are ordered,
tasks within a phase can often go in parallel. `[x]` = done, `[~]` = partially done.

> Cross-repo critical path: **Phase 3 (API contract) must land before `nyvra-ui` builds screens.**
> The frontend can start its design work (`nyvra-ui/TODO.md` Phase 1) immediately, in parallel.

---

## Phase 0 — Skeleton  ✅ done

- [x] Spring Boot 3.3 + Spring Modulith app, package `com.rohit.nyvra`
- [x] OAuth2 resource-server security (Keycloak/OIDC), CORS, method security
- [x] `user` module: `UserProfile` + JIT provisioning + `GET /api/v1/users/me`
- [x] Flyway `V1` (user_profile, user_preferences, data_consent_record)
- [x] Profiles `local/dev/staging/prod` + `test`; runtime config via env vars
- [x] docker-compose (Postgres/Timescale, Redis, RabbitMQ, MinIO, Keycloak) + realm import
- [x] Design docs: PROJECT_OVERVIEW, TECH_STACK, DOMAIN_MODEL, DATABASE_DESIGN, FINANCIAL_RULES, HEALTH_SCORE_SPEC, ENVIRONMENTS

## Phase 1 — Foundation & environments

- [ ] Bring the local stack up end-to-end: `docker compose up`, app boots on `local`, Keycloak realm imports, get a token for `demo` and call `/api/v1/users/me` successfully
- [ ] CI pipeline (GitHub Actions): build + unit tests + `flyway:validate` + fail on OpenAPI drift
- [ ] Testcontainers base (`@ServiceConnection` for Postgres) for integration tests
- [ ] `ARCHITECTURE.md` — Modulith module map, sync vs async data flow, diagram
- [ ] Decide + wire structured JSON logging for non-local profiles
- [ ] Confirm secret strategy for dev/staging/prod (see `design-docs/ENVIRONMENTS.md` §6)

## Phase 2 — Database design finalized (per `design-docs/DATABASE_DESIGN.md`)

- [ ] `V2` accounts + transactions (+ monthly declarative partitioning, dedup unique index)
- [ ] `V3` income (income_source, income_entry with no-overlap exclusion constraint, payslip_document)
- [ ] `V4` expense + category (+ seed system category tree, categorisation_rule)
- [ ] `V5` portfolio (instrument, portfolio_holding, corporate_action) + instrument reference seed
- [ ] `V6` net worth (net_worth_snapshot, manual_asset_liability)
- [ ] `V7` analytics (health_score, insight, dashboard_summary_cache)
- [ ] `V8` ingestion (aggregator_consent, fetch_session, raw_financial_record, normalisation_run)
- [ ] Enable **TimescaleDB**: `CREATE EXTENSION`, hypertables for price_quote / valuation_snapshot / net_worth_snapshot / health_score, continuous aggregates for trends
- [ ] **Field-level encryption**: AES-GCM attribute converter, key from secret store, dual-key rotation; migrate `user_profile.email` → `bytea` + `email_hash` blind index; encrypt `transaction.narration/counterparty`, `raw_financial_record.raw_json`
- [ ] JPA entities + Spring Data repositories per module (map to `DOMAIN_MODEL.md` aggregates)
- [ ] Retention/purge scheduled job for `raw_financial_record` (`purge_after`)

## Phase 3 — API contract  ← blocks `nyvra-ui` screens

- [ ] `API_DESIGN.md` — REST conventions, pagination (page/size + cursor), sort/filter, error envelope (already `ApiError`), idempotency keys for writes, date/money JSON formats
- [ ] DTOs (records) + MapStruct mappers per module; never expose entities
- [ ] Controllers per module: `Accounts`, `Income`, `Expense`, `Portfolio`, `NetWorth`, `Analytics`, `User` — CRUD + list endpoints with ownership checks
- [ ] Consistent auth: `@PreAuthorize`, `CurrentUserService` on every user-scoped query
- [ ] Export `src/main/resources/openapi/nyvra-api-v1.yaml`, commit it, wire CI drift check
- [ ] Publish a "v1 frozen" tag once the frontend depends on it

## Phase 4 — Core business logic (the product's brain)

- [ ] `FINANCIAL_RULES` engine: YAML param loader (`ruleset_version`), one class per rule group, data-sufficiency gates, precedence ordering — full unit tests
- [ ] `HEALTH_SCORE` engine: 6 sub-scores, weight redistribution, confidence, hard caps, `inputsHash` skip-recompute — tests including the `HEALTH_SCORE_SPEC.md` worked example
- [ ] Net-worth aggregation service + guaranteed daily snapshot job (ShedLock leader-only)
- [ ] Spending categorisation + rule engine + `SpendingHabitSnapshot` recompute
- [ ] Portfolio valuation + XIRR + allocation-vs-target + `AllocationDrifted` events
- [ ] Goal tracking + progress calc
- [ ] Insights generation from rule breaches (severity, evidence, dedupe/supersede)

## Phase 5 — Ingestion pipeline (RBI Account Aggregator)

- [ ] `INTEGRATIONS.md`, `DATA_INGESTION.md`, `EVENT_DESIGN.md`
- [ ] RabbitMQ topology: exchanges, queues, DLQ, retry policy, message schemas
- [ ] `aggregator` module: AA client for a sandbox provider (Setu / Finvu / OneMoney) behind a clean internal interface
- [ ] Consent flow: request → user approval redirect → consent handle/artefact storage → state machine
- [ ] FI data fetch: session request, poll/callback, raw record persistence (encrypted)
- [ ] Normalisation → idempotent domain events (`dedupKey`) consumed by Accounts / Portfolio / Income
- [ ] Resilience4j: retries, circuit breakers, rate limiters, backpressure around AA + feeds
- [ ] Price / NAV feed integration → `PriceQuote` ingestion + daily valuation job
- [ ] (Optional) Gmail API supplement — hints only, never creates transactions

## Phase 6 — Analytics & dashboard endpoints

- [ ] `GET /api/v1/analytics/dashboard-summary` (Redis-cached, durable rebuild source)
- [ ] `GET /api/v1/analytics/trends` backed by Timescale continuous aggregates
- [ ] Insights list / dismiss endpoints
- [ ] Health-score history endpoint (score trend line)

## Phase 7 — Hardening & prod readiness

- [ ] `SECURITY.md` — threat model, audit logging, secrets, input limits
- [ ] `COMPLIANCE.md` — DPDP: consent records, retention jobs, **erasure workflow**, **data export**, processing register
- [ ] `OBSERVABILITY.md` — metrics, tracing (OTLP), Sentry, alert catalogue
- [ ] `TEST_STRATEGY.md` + integration test suite (Testcontainers) + financial-calc correctness suite + `spring-modulith` `verify()`
- [ ] `CI_CD.md` — env promotion, migrations as a gated job (not on boot in staging/prod), rollback = redeploy previous tag
- [ ] Rate limiting, security headers, request-size limits
- [ ] Choose cloud provider → fill `ENVIRONMENTS.md` §7 mapping → provision dev / staging / prod (India region for DPDP)
- [ ] Backup + PITR runbook; quarterly restore drill
- [ ] Load / perf test the dashboard read path; tune Hikari pool + read replica

## Phase 8 — Launch

- [ ] Production Keycloak hardening (MFA, brute-force, session policy, realm export via CI)
- [ ] Privacy policy + consent artefacts reviewed
- [ ] Data-residency confirmation
- [ ] Go-live checklist + on-call/alerting in place
