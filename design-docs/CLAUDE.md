# nyvra-backend — Design Docs Index (CLAUDE.md)

> Entry point for the backend design documentation. Read this first, then follow the links.
> This folder describes **intended design**. Where it disagrees with code, the code is either wrong or the doc is stale — raise it, don't silently follow one.

---

## What nyvra is

A personal-finance "accountant" for the Indian market. It ingests a user's financial data
(bank accounts, investments, income, expenses) — primarily through the **RBI Account Aggregator (AA)
framework** — and tells the user *whether they are in control of their money* via a single
**health score** plus supporting breakdowns (net worth, spending habits, portfolio allocation, trends).

- **Region focus:** India — Account Aggregator, EPF/NPS, broker APIs (Kite/Zerodha), INR, DPDP Act 2023.
- **Users:** individuals. Multi-tenant by `user_id`; no org/household sharing in v1.
- **Not in scope for v1:** tax filing, bill pay / money movement, advisory/robo-investing, multi-currency accounting (FX is display-only), household/shared budgets.

---

## Stack (authoritative — keep in sync with `pom.xml`)

| Concern | Choice | Notes |
|---|---|---|
| Language / runtime | Java 21 LTS | Virtual threads for the I/O-heavy ingestion workload |
| Framework | Spring Boot 3.3.x + **Spring Modulith** | Modular monolith; module boundaries enforced by Modulith |
| Build | Maven | `./mvnw` |
| Primary DB | PostgreSQL 16 | ACID is non-negotiable for finance |
| Time-series | TimescaleDB extension on the same Postgres | Portfolio value history, price history, score history |
| Migrations | Flyway | `src/main/resources/db/migration`, never edit an applied migration |
| Cache | Redis | Computed dashboards, NAVs, FX rates, AA session state |
| Async / messaging | RabbitMQ | Ingestion pipeline; move to Kafka only if volume demands |
| Object storage | S3-compatible (MinIO locally) | Payslips, hike letters, statements |
| Auth | **Self-hosted Keycloak (OAuth2 / OIDC)** | See "Auth model" below — this supersedes the hand-rolled JWT flow in `nyvra-backend-structure.md` |
| API | REST under `/api/v1`, OpenAPI via springdoc | `nyvra-api-v1.yaml` is the FE contract source of truth |
| Entity ↔ DTO | MapStruct | Never expose JPA entities over the API |
| Secrets | Externalised; cloud-agnostic (see `ENVIRONMENTS.md`) | No secrets in the repo or in `application-*.yml` |

Deployment target is deliberately **cloud-agnostic** for now — see `ENVIRONMENTS.md` for the role-based
description and the single reference mapping.

---

## The three locked architectural decisions

These come from the planning doc (now captured in `PROJECT_OVERVIEW.md`) and are not up for re-litigation without an ADR:

1. **Never store raw card numbers or bank credentials.** Store last 4 digits, network, and a user label only.
   Bank accounts: masked identifiers, never full credentials. This keeps us out of PCI-DSS scope.
2. **Use the RBI Account Aggregator framework** (via Setu / Finvu / OneMoney) for financial data — not email/SMS scraping.
   Gmail API is a *supplement* only. Plan for DPDP Act 2023 from day one.
3. **Modular monolith, not microservices.** Spring Modulith on PostgreSQL. Scale horizontally later by keeping the app stateless.

---

## Auth model (decision)

**Chosen:** self-hosted **Keycloak** as the OpenID Connect provider.

- Frontend (Angular SPA) uses **Authorization Code + PKCE**. Keycloak issues access + refresh tokens.
- Backend is a **resource server** only: it validates the JWT access token against Keycloak's JWKS
  (`spring-boot-starter-oauth2-resource-server`). No login/refresh/password endpoints in nyvra-backend.
- User identity in our DB is keyed by the Keycloak `sub` (subject) claim. We store a **profile** row, never credentials.
- Roles/claims: `ROLE_USER` for everyone; `ROLE_ADMIN` reserved for internal ops tooling.
- One Keycloak **realm** (`nyvra`) with a per-environment realm config; one public client for the SPA,
  one confidential client for any server-to-server calls. Details per environment in `ENVIRONMENTS.md`.

> `nyvra-backend-structure.md`'s JWT/`AuthController` sections have been updated to reflect this
> (the doc now points here). Keep the `/api/v1` prefix and versioning rules from that doc.

---

## Documents in this folder

| Doc | Status | Purpose |
|---|---|---|
| `CLAUDE.md` (this file) | ✅ | Index, stack, locked decisions, auth model |
| `PROJECT_OVERVIEW.md` | ✅ | Vision, goals, scope, explicit out-of-scope, region/compliance, scaling strategy, roadmap |
| `TECH_STACK.md` | ✅ | Every component with version + rationale (backend, data, async, infra, frontend summary) |
| `nyvra-backend-structure.md` | ✅ (pre-existing, updated) | Repo layout and package structure. Auth sections rewritten for OIDC |
| `DOMAIN_MODEL.md` | ✅ | Bounded contexts, aggregates, entities, invariants, context map |
| `DATABASE_DESIGN.md` | ✅ | Schema, keys, indexes, encryption, partitioning, migration conventions |
| `FINANCIAL_RULES.md` | ✅ | The parameterised financial logic behind "is the user in control" |
| `HEALTH_SCORE_SPEC.md` | ✅ | Exact inputs, weights, formula, bands, and worked example for the homepage score |
| `ENVIRONMENTS.md` | ✅ | Spring profiles `local` / `dev` / `staging` / `prod`, config & secret strategy, promotion flow |

> **Provenance:** the planning PDF (`Personal Finance App Plan.pdf`) that seeded this repo has been
> fully decomposed into `PROJECT_OVERVIEW.md` + `TECH_STACK.md` + the docs above, and deleted so there
> is one source of truth.

### Planned / not yet written (add when the project needs them)

Priorities: **[P0]** must-have, **[P1]** important (carried over from the planning doc).

| Doc | Pri | Purpose |
|---|---|---|
| `ARCHITECTURE.md` | P0 | System diagram, Modulith module map, data flow, sync vs async paths |
| `API_DESIGN.md` | P0 | REST conventions, pagination, error envelope, idempotency; backed by `nyvra-api-v1.yaml` |
| `INTEGRATIONS.md` | P0 | AA (Setu/Finvu), broker APIs, price/NAV feeds, Gmail API — auth, rate limits, retries, backpressure |
| `SECURITY.md` | P0 | Threat model, field-level encryption, secrets, DPDP data-subject rights, audit logging |
| `DATA_INGESTION.md` | P0 | AA consent → fetch → normalise → categorise → dedup pipeline; mapping rules |
| `EVENT_DESIGN.md` | P1 | RabbitMQ exchanges/queues, message schemas, retry/DLQ policy, ordering guarantees |
| `COMPLIANCE.md` | P1 | DPDP Act 2023: consent records, retention, deletion/erasure, data-processing register |
| `TEST_STRATEGY.md` | P0 | Unit/integration/e2e split, Testcontainers, what to mock, coverage targets, financial-calc correctness |
| `CI_CD.md` | P1 | Pipeline stages, environment promotion, migration gating, rollback |
| `OBSERVABILITY.md` | P1 | Structured logging, metrics, tracing, alert catalogue |
| `GLOSSARY.md` | P1 | NAV, XIRR, corpus, vesting, allocation, emergency fund, etc. (shared with the UI repo) |

---

## Conventions quick reference

- **Package by module, then by layer:** `com.rohit.nyvra.<module>.<controller|service|repository|model|dto>`.
- **Controllers are thin.** All calculation lives in services and is unit-tested against `FINANCIAL_RULES.md` / `HEALTH_SCORE_SPEC.md`.
- **Money:** `BigDecimal`, scale 2 for INR amounts, scale 6 for unit prices/NAV/FX. Never `double`. Currency code stored explicitly (`INR` default).
- **Time:** store `Instant` (UTC) for events; store `LocalDate` for accounting dates. User's display timezone is `Asia/Kolkata`.
- **IDs:** UUID v7 primary keys.
- **Migrations:** additive only; `V<n>__<snake_case>.sql`; one logical change per migration.
- **No entity leaves the service layer** — map to DTO first.
