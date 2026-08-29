# CLAUDE.md — nyvra-service

Entry point for Claude Code (CLI + IDE extensions). Read this first, then open the design doc that
matches the task. Keep this file short; detail lives in `design-docs/`.

## What this is

Backend for **nyvra** — a personal-finance "accountant" for the Indian market.
Java 21 · Spring Boot 3.3 · **Spring Modulith** (modular monolith) · PostgreSQL + TimescaleDB ·
Flyway · OAuth2 **resource server** (Keycloak / OIDC) · springdoc-openapi.
Package root: `com.rohit.nyvra`. All REST endpoints under `/api/v1`.

The Angular web client is a separate repo: `nyvra-ui`.

## Which design doc to read for which task

| If you're working on… | Read |
|---|---|
| Anything — vision, scope, the locked decisions | `design-docs/PROJECT_OVERVIEW.md` |
| Adding/refactoring a module, bounded-context questions | `design-docs/DOMAIN_MODEL.md` |
| Entities, tables, migrations, indexes, encryption | `design-docs/DATABASE_DESIGN.md` + `design-docs/nyvra-backend-structure.md` |
| Any financial calculation, thresholds, ratios | `design-docs/FINANCIAL_RULES.md` |
| The homepage health score | `design-docs/HEALTH_SCORE_SPEC.md` |
| Config, profiles, secrets, Keycloak, deployment | `design-docs/ENVIRONMENTS.md` |
| Stack versions / library choices / rationale | `design-docs/TECH_STACK.md` |
| Package layout, where a class belongs | `design-docs/nyvra-backend-structure.md` |
| Index of everything + planned docs | `design-docs/CLAUDE.md` |

`design-docs/CLAUDE.md` is the fuller index (it also loads automatically when you edit files under
`design-docs/`). This root file is the always-on summary.

## Non-negotiable rules

- **Never store** raw card PANs, CVV, or bank credentials. Card = last4 + network + label only;
  accounts = masked identifiers only. (Keeps us out of PCI-DSS scope.)
- **Money:** `BigDecimal`, scale 2 for amounts / scale 6 for prices-NAV-FX-qty. Never `double`/`float`.
  Every money value carries an explicit currency (`INR` default).
- **Time:** `Instant` (UTC) for events; `LocalDate` for accounting dates. User display TZ is `Asia/Kolkata`.
- **IDs:** UUID primary keys, generated in the app.
- **Auth:** this service only *validates* Keycloak-issued JWTs. No login/refresh/password endpoints,
  no `AuthController`. User rows are keyed by the token `sub`; never store credentials.
- **DTOs at the edge:** never return JPA entities from a controller. Map to a DTO/record.
- **Controllers stay thin:** all calculation logic lives in services and is unit-tested against
  `FINANCIAL_RULES.md` / `HEALTH_SCORE_SPEC.md`.
- **Migrations:** additive only. `V<n>__snake_case.sql`. Never edit an applied migration.
- **Secrets:** never in the repo, never in `application-*.yml`. Env vars / secret store only.
- **API versioning:** breaking changes go to `/api/v2`; don't mutate `/api/v1` in place.
- Financial rule params and the score formula are **config + spec driven** — don't hard-code numbers
  from the specs into Java.

## Commands

```bash
cp .env.example .env && docker compose up -d   # Postgres/Timescale, Redis, RabbitMQ, MinIO, Keycloak
./mvnw spring-boot:run                          # app on :8080, profile 'local'
./mvnw clean verify                             # compile + tests
```

Swagger UI `http://localhost:8080/swagger-ui.html` · Keycloak `http://localhost:8081` (admin/admin) ·
local login user `demo` / `demo`.

## Layout

```
src/main/java/com/rohit/nyvra/
  config/            SecurityConfig, OpenApiConfig, JacksonConfig
  common/exception/  ApiError, GlobalExceptionHandler
  user/              first real module — profile, JIT provisioning, GET /users/me
  ingestion/ accounts/ income/ expense/ networth/ portfolio/ aggregator/ analytics/
                     module placeholders (package-info) — build out per DOMAIN_MODEL.md
src/main/resources/
  application.yml + application-{local,dev,staging,prod}.yml
  db/migration/      Flyway
  openapi/           exported contract for the frontend client
```

## Status & what to work on next

Skeleton stage. `user` is the only implemented module. TimescaleDB hypertables, table partitioning,
and field-level encryption are defined in `DATABASE_DESIGN.md` but deferred to later migrations.

See [`TODO.md`](TODO.md) for the prioritised roadmap (phases: foundation → DB migrations → API
contract → business logic → ingestion → hardening → launch).
