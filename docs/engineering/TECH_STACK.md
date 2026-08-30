# TECH_STACK.md — nyvra

Every component, its version/target, and *why*. Keep this in sync with `pom.xml` (backend) and
`nyvra-ui/package.json` (frontend). Rationale matters: it's what keeps regeneration and upgrades
consistent.

Legend: **Now** = used from day one · **Planned** = adopt when the feature that needs it lands.

---

## Backend

| Component | Version / target | Status | Rationale |
|---|---|---|---|
| Java | 21 LTS | Now | Virtual threads (Project Loom) suit the I/O-heavy ingestion workload (many external API calls); LTS support window |
| Spring Boot | 3.3.x | Now | Current GA line; Java 21 baseline; broad ecosystem |
| Spring Modulith | 1.2.x (matches Boot) | Now | Enforces module boundaries in a monolith; can extract a module to a service later without a rewrite |
| Build | Maven 3.9.x + wrapper | Now | Team familiarity; reproducible via `./mvnw` |
| Web | `spring-boot-starter-web` (Tomcat) | Now | Virtual-threads enabled (`spring.threads.virtual.enabled=true`) |
| Persistence | `spring-boot-starter-data-jpa` (Hibernate 6) | Now | Standard; entities per `DATABASE_DESIGN.md` |
| Migrations | Flyway | Now | Versioned SQL, `V<n>__*.sql`; never edit an applied migration |
| DB driver | PostgreSQL JDBC | Now | |
| Mapping | MapStruct 1.5.x | Now | Compile-time entity↔DTO mapping; never expose entities over the API |
| Validation | `spring-boot-starter-validation` (Jakarta) | Now | Request DTO constraints |
| Security | `spring-boot-starter-oauth2-resource-server` | Now | Validates Keycloak-issued JWT access tokens; no custom token code |
| API docs | springdoc-openapi 2.x | Now | `/v3/api-docs` + Swagger UI; source of the FE's generated client contract |
| Caching | `spring-boot-starter-data-redis` (Lettuce) | Now | Dashboard/NAV/FX caches, AA session state |
| Messaging | `spring-boot-starter-amqp` (RabbitMQ) | Now | Ingestion pipeline; simpler than Kafka; move to Kafka only if volume demands |
| Object storage | AWS SDK v2 `s3` (S3-compatible) | Now | Payslips, statements; MinIO locally |
| Scheduling / locks | Spring `@Scheduled` + ShedLock | Now | Daily snapshot & retention jobs; ShedLock so only one instance runs them |
| Resilience | Resilience4j | Planned | Retries, circuit breakers, rate limiters around AA / broker / price feeds |
| Time-series | TimescaleDB (Postgres extension) | Now | Hypertables + continuous aggregates for price / valuation / net-worth / score history |
| Observability | Micrometer + Actuator (Prometheus registry) | Now | Metrics; management port locked down in prod |
| Tracing | Micrometer Tracing + OTLP exporter | Planned | Distributed traces once there's more than one process |
| Error tracking | Sentry Spring integration | Planned | |
| Tests | JUnit 5, Spring Boot Test, **Testcontainers** (Postgres, Redis, RabbitMQ, Keycloak), REST Assured | Now | Real dependencies in integration tests; heavy focus on financial-calc correctness |
| Field encryption | AES-GCM via JDK `javax.crypto` (or Tink) | Now | Application-layer encryption for 🔒 columns; key from the env secret store, dual-key rotation |

**Package root:** `com.rohit.nyvra`. **Architecture:** modular monolith. **API:** REST under `/api/v1`.

---

## Data

| Store | Target | Status | Use |
|---|---|---|---|
| PostgreSQL | 16 | Now | Primary store — ACID is non-negotiable for finance |
| TimescaleDB | latest for PG16 | Now | Time-series tables (see `DATABASE_DESIGN.md`) |
| Redis | 7.x | Now | Cache + ephemeral session/token state (with TTL) |
| Object storage | S3-compatible | Now | Documents; MinIO locally, cloud bucket per env |

---

## Async / ingestion

| Component | Target | Status | Notes |
|---|---|---|---|
| RabbitMQ | 3.13.x | Now | Exchanges/queues + DLQ per `EVENT_DESIGN.md` (planned); quorum queues in prod |
| Kafka | — | Not planned | Only if event volume clearly outgrows RabbitMQ |

---

## Identity

| Component | Target | Status | Notes |
|---|---|---|---|
| Keycloak | 25.x (or current) | Now | Self-hosted OIDC provider; realm `nyvra`, clients `nyvra-web` (public/PKCE) + `nyvra-api` |
| | | | Own container + own Postgres DB/schema; realm version-controlled as JSON, applied via CI |

Decision record: chosen over hand-rolled JWT (security-critical code we'd own) and over managed IdPs
(per-MAU cost + external dependency in the login path). See `nyvra-service/docs/CLAUDE.md` → Auth model.

---

## Frontend (summary — full detail in the `nyvra-ui` repo)

| Component | Target | Status | Rationale |
|---|---|---|---|
| Framework | **Angular** (latest stable) + TypeScript `strict` | Now | Founder is already fluent; enforced structure suits a solo maintainer. See `nyvra-ui/design-docs/FRONTEND_FRAMEWORK_EVALUATION.md` |
| Build | Angular CLI (esbuild) | Now | `ng serve` / `ng build --configuration <env>` |
| Components | Angular Material | Now | Base UI kit; app components layered in `shared/` |
| Charts | Apache ECharts via `ngx-echarts` | Now | Large time-series and finance chart types |
| Auth | `angular-auth-oidc-client` | Now | Authorization Code + PKCE against Keycloak |
| API client | `@openapitools/openapi-generator-cli` → `typescript-angular` | Now | Generated from the backend's OpenAPI; never hand-edited |
| State | Signals (component) + RxJS (streams) + thin query/cache layer (server state) | Now | |
| Runtime config | `/config.json` loaded via app initializer | Now | One build promoted across envs; see `nyvra-ui/design-docs/ENVIRONMENTS.md` |
| Tests | Jest / Web Test Runner + Playwright (e2e, a11y) | Now | |

---

## Infrastructure & secrets

| Concern | Choice | Status | Notes |
|---|---|---|---|
| Containerisation | Docker | Now | Multi-stage build; distroless/JRE base for the backend image |
| Local orchestration | Docker Compose | Now | Postgres/Timescale, Redis, RabbitMQ, MinIO, Keycloak (+ its DB) |
| Deploy target | **Cloud-agnostic** for now | Now | Managed container service; **not** Kubernetes early. Reference mapping (AWS/GCP/Azure) in `ENVIRONMENTS.md` §7 |
| Secrets | Platform secret manager / Vault-style provider | Now | Never in the repo or `application-*.yml`; injected as env vars / resolved at boot |
| CI/CD | Pipeline with migration-gated promotion | Planned (`CI_CD.md`) | Immutable image tags; rollback = redeploy previous tag |
| Environments | `local`, `dev`, `staging`, `prod` | Now | Spring profiles + FE runtime config; see `ENVIRONMENTS.md` |

---

## Explicitly rejected / deferred

| Option | Decision | Why |
|---|---|---|
| Microservices | Rejected for v1 | Operational cost a solo founder doesn't need; modular monolith scales far enough |
| Kubernetes | Deferred | Managed container service first; adopt only when scaling/ops genuinely require it |
| Kafka | Deferred | RabbitMQ is simpler and sufficient until proven otherwise |
| Hand-rolled JWT auth | Rejected | Security-critical code; Keycloak is battle-tested |
| React frontend | Rejected | Analysis was close; founder's Angular fluency decides it |
| Storing raw PAN / bank credentials | Rejected (hard rule) | PCI-DSS scope; see `PROJECT_OVERVIEW.md` §4.1 |
| Email/SMS scraping as primary source | Rejected (hard rule) | Fragile; AA framework is the sanctioned path |
