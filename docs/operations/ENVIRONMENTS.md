# ENVIRONMENTS.md — nyvra-backend

Environment and configuration strategy for the backend, from a developer laptop through to
production. Deployment is **cloud-agnostic** for now: this doc describes each dependency by *role*,
gives a Docker-Compose setup for local, and one reference cloud mapping. When a cloud is chosen,
only §7 needs to change.

---

## 1. Environments

| Env | Spring profile(s) | Purpose | Who deploys | Data |
|---|---|---|---|---|
| **local** | `local` | Individual dev on a laptop | the dev, manually | Disposable; seeded from migrations + `V__seed_*` |
| **dev** | `dev` | Shared integration sandbox; latest `main` | CI on merge to `main` | Disposable; synthetic only. Reset weekly |
| **staging** | `staging` | Production-like; release validation, UAT, perf, migration dry-run | CI on release tag / manual promote | Synthetic PII only. **No real user data** |
| **prod** | `prod` | Real users | CI on release tag, manual approval gate | Real. Backups, PITR, strict access |

`SPRING_PROFILES_ACTIVE` selects the profile. `local` may additionally activate `local-<name>` for personal tweaks (git-ignored file).

There is intentionally **no `test` environment** — automated tests run with `@ActiveProfiles("test")`
against Testcontainers, config in `src/test/resources/application-test.yml`.

---

## 2. Configuration layering (precedence, lowest → highest)

1. `application.yml` — profile-independent defaults, **no secrets, no environment specifics**.
2. `application-<profile>.yml` — per-environment non-secret settings (pool sizes, log levels, feature flags, URLs that are not secret).
3. **Environment variables** — everything environment-specific that is injected at deploy time.
4. **Secret store** — secrets resolved at startup via Spring Cloud Config / `spring-cloud-vault` style provider, or plain env vars injected from the platform's secret manager. Never in files 1–3.
5. Command-line `--` overrides — local debugging only.

Rules:
- **No secret is ever committed.** `application-*.yml` is scanned in CI for secret-looking strings.
- `application.yml` must boot with an in-memory/dummy config only under `test`; every real profile must fail fast if a required env var is missing (`spring.config.import` + `@Validated @ConfigurationProperties`).
- Config keys use `nyvra.*` namespace for our own settings.
- Local uses a git-ignored `.env` file loaded by Docker Compose / an IDE plugin; `.env.example` is committed.

---

## 3. Settings that vary by environment

| Key | local | dev | staging | prod |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | `dev` | `staging` | `prod` |
| Log level (`logging.level.root`) | `DEBUG` for `com.rohit.nyvra`, `INFO` root | `INFO` | `INFO` | `WARN` root, `INFO` app |
| Log format | pretty console | JSON | JSON | JSON |
| `spring.jpa.hibernate.ddl-auto` | `validate` | `validate` | `validate` | `validate` (Flyway owns schema) |
| Flyway `clean` enabled | `true` | `false` | `false` | `false` (also disabled via `spring.flyway.clean-disabled=true`) |
| Flyway migrate on boot | `true` | `true` | `true` | **`false`** — run as a separate gated CI step, then start app |
| Hikari `maximum-pool-size` | `5` | `10` | `20` | `30` (tune to DB `max_connections`) |
| `server.forward-headers-strategy` | `none` | `framework` | `framework` | `framework` (behind LB/proxy) |
| Swagger UI (`springdoc.swagger-ui.enabled`) | `true` | `true` | `true` | **`false`**; `/v3/api-docs` also gated to internal network |
| CORS allowed origins | `http://localhost:4200`, `:5173` | `https://dev.nyvra.app` | `https://staging.nyvra.app` | `https://app.nyvra.app` |
| Actuator exposure | `*` | `health,info,metrics,prometheus` | same as dev | `health,info,prometheus` only, on a separate management port, network-restricted |
| Rate limiting | off | lenient | prod-like | enforced |
| External integrations | sandbox / mocked | sandbox | sandbox (or limited real) | real |
| Feature flags | all on | all on | release set | release set |
| Scheduled jobs (`nyvra.jobs.enabled`) | `true` | `true` | `true` | `true`, but run on a single leader instance (ShedLock) |

---

## 4. Dependencies by role

| Role | local | dev / staging / prod |
|---|---|---|
| **Relational DB** | Postgres 16 + TimescaleDB in Docker Compose | Managed Postgres with the TimescaleDB extension (or self-managed); read replica in prod |
| **Cache / ephemeral session store** | Redis in Compose | Managed Redis; used for dashboard cache, NAV/FX cache, AA session tokens (with TTL) |
| **Message broker** | RabbitMQ in Compose | Managed RabbitMQ; mirrored/quorum queues in prod |
| **Object storage** | MinIO in Compose (S3 API) | S3-compatible bucket per env; SSE enabled; lifecycle rules for old statements |
| **Identity provider** | Keycloak in Compose, realm imported from `keycloak/realm-nyvra.json` | Keycloak deployed per env (own container + its own Postgres schema/db); realm exported/imported through CI |
| **Secret store** | `.env` file (local only) | Platform secret manager / Vault; injected as env vars or resolved at boot |
| **Config** | files + `.env` | files + env vars (+ optional central config service) |

### Local `docker-compose.yml` services
`postgres` (timescale/timescaledb-ha image), `redis`, `rabbitmq` (management plugin), `minio` + `minio-mc` (bucket bootstrap), `keycloak` + `keycloak-db`, and optional `mailhog` for outbound-email testing.
App itself runs on the host via `./mvnw spring-boot:run` (fast reload) or as a Compose service for a full-stack smoke test.

---

## 5. Auth / Keycloak per environment

Backend is a **resource server only** (`spring-boot-starter-oauth2-resource-server`). It needs:

| Key (env var) | Meaning | local | prod |
|---|---|---|---|
| `NYVRA_OIDC_ISSUER_URI` | Keycloak realm issuer | `http://localhost:8081/realms/nyvra` | `https://auth.nyvra.app/realms/nyvra` |
| `NYVRA_OIDC_JWK_SET_URI` | derived from issuer if standard | (auto) | (auto) |
| `NYVRA_OIDC_AUDIENCE` | expected `aud` / client id | `nyvra-api` | `nyvra-api` |
| `NYVRA_CORS_ALLOWED_ORIGINS` | SPA origin(s) | localhost dev servers | `https://app.nyvra.app` |

- One realm `nyvra` per environment (separate Keycloak instance per env — no shared realm across envs).
- Clients: `nyvra-web` (public, PKCE, for the SPA) and `nyvra-api` (bearer-only / audience). Optional `nyvra-bff` confidential client if a BFF is added later.
- Realm config (roles `ROLE_USER`, `ROLE_ADMIN`; token lifespans: access ~15 min, refresh ~30 days rotating; brute-force detection on) is version-controlled as a realm JSON and applied via CI, not clicked in the admin console.
- Local realm ships with two seed users (`demo`, `admin`) — **local only**, never exported to other envs.

---

## 6. Secrets inventory

Required per environment (names indicative; supplied via the platform's secret manager, never files):

| Secret | Used for |
|---|---|
| `NYVRA_DB_URL` / `NYVRA_DB_USERNAME` / `NYVRA_DB_PASSWORD` | Postgres |
| `NYVRA_REDIS_URL` / `NYVRA_REDIS_PASSWORD` | Redis |
| `NYVRA_RABBITMQ_URL` / user / password | Broker |
| `NYVRA_S3_ENDPOINT` / `NYVRA_S3_BUCKET` / `NYVRA_S3_ACCESS_KEY` / `NYVRA_S3_SECRET_KEY` | Object storage |
| `NYVRA_FIELD_ENCRYPTION_KEY` (+ `..._KEY_PREVIOUS` for rotation) | 🔒 column encryption (see `DATABASE_DESIGN.md`) — **backed up separately from the DB** |
| `NYVRA_OIDC_ISSUER_URI` etc. | Keycloak (issuer is not secret but is env-injected) |
| `NYVRA_AA_CLIENT_ID` / `NYVRA_AA_CLIENT_SECRET` / `NYVRA_AA_BASE_URL` | Account Aggregator (Setu/Finvu) — sandbox creds for non-prod |
| `NYVRA_PRICE_FEED_API_KEY` | NAV / price feed |
| `NYVRA_GMAIL_OAUTH_CLIENT_ID` / `_SECRET` | Gmail API supplement (optional) |
| `NYVRA_SENTRY_DSN` / observability tokens | Error + metrics pipelines |

Rotation: field-encryption key supports dual-key (current + previous) for zero-downtime rotation;
re-encrypt job walks 🔒 rows in the background. All other secrets rotated via the platform manager
with a rolling restart.

---

## 7. Reference cloud mapping (fill in when a provider is chosen)

| Role | AWS | GCP | Azure |
|---|---|---|---|
| Container runtime | ECS Fargate / EKS | Cloud Run / GKE | Container Apps / AKS |
| Postgres + Timescale | RDS/Aurora Postgres (self-install Timescale) or Timescale Cloud | Cloud SQL (+ Timescale) or Timescale Cloud | Azure DB for Postgres Flexible |
| Redis | ElastiCache | Memorystore | Azure Cache for Redis |
| Broker | Amazon MQ (RabbitMQ) | — (self-host) / Pub/Sub if refactor | Service Bus (refactor) |
| Object storage | S3 | GCS | Blob Storage |
| Secrets | Secrets Manager / SSM | Secret Manager | Key Vault |
| Data residency | `ap-south-1` (Mumbai) | `asia-south1` | `centralindia` |

Keep India data residency in mind for DPDP.

---

## 8. Promotion flow

```
feature branch ──PR──▶ main ──CI──▶ dev (auto)
                                     │  smoke + integration tests green
                                     ▼
                              release tag vX.Y.Z
                                     │
                          ┌──────────┴───────────┐
                          ▼                      ▼
                    migrate staging        (manual approve)
                    deploy staging  ─────▶  migrate prod (gated job)
                    UAT / perf green        deploy prod (rolling)
```

- **Migrations run as their own CI step** before the app image is deployed, against a backup-verified DB in staging/prod. App boot does not migrate in staging/prod (`spring.flyway.enabled=false` at runtime there; a dedicated `flyway:migrate` job does it).
- Every deploy is a specific immutable image tag; rollback = redeploy previous tag. Backward-incompatible migrations follow the two-step expand/contract rule in `DATABASE_DESIGN.md` so rollback is always safe.
- Config changes go through the same PR review as code.

---

## 9. Local quick start

```bash
cp .env.example .env            # fill in sandbox creds
docker compose up -d            # postgres, redis, rabbitmq, minio, keycloak
./mvnw flyway:migrate           # or let local profile migrate on boot
./mvnw spring-boot:run          # app on :8080, profile 'local'
```
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Keycloak admin: `http://localhost:8081` (`admin` / `admin` — local only)
- MinIO console: `http://localhost:9001`
- RabbitMQ console: `http://localhost:15672`
