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
| `NYVRA_BLIND_INDEX_KEY` | HMAC key for blind-index lookup columns (e.g. `email_hash`) on 🔒 columns that still need equality lookups — see `DATABASE_DESIGN.md` → Field-level encryption |
| `NYVRA_OIDC_ISSUER_URI` etc. | Keycloak (issuer is not secret but is env-injected) |
| `NYVRA_AA_CLIENT_ID` / `NYVRA_AA_CLIENT_SECRET` / `NYVRA_AA_BASE_URL` | Account Aggregator (Setu/Finvu) — sandbox creds for non-prod |
| `NYVRA_PRICE_FEED_API_KEY` | NAV / price feed |
| `NYVRA_GMAIL_OAUTH_CLIENT_ID` / `_SECRET` | Gmail API supplement (optional) |
| `NYVRA_SENTRY_DSN` / observability tokens | Error + metrics pipelines |

### 6.1 Provider abstraction (decision)

**Now:** plain environment variables everywhere — `.env` (git-ignored) locally, injected by the CI/CD
platform per environment otherwise. No secret is ever read from a file checked into the repo or from
`application-*.yml`. **Later:** swap the injection mechanism for Vault or the chosen cloud's secret
manager (AWS Secrets Manager, GCP Secret Manager, Azure Key Vault — see §7) without any application
code change, since the app only ever reads `System.getenv(...)`/Spring property placeholders — *how*
the env var gets populated at deploy time is entirely a platform/CI concern, not an app concern. This
is why there's no secrets-client SDK dependency in `pom.xml` today.

### 6.2 Generating local secrets

Not needed to boot the app today — field-level encryption isn't implemented yet (`TODO.md` Phase
2.10) — but generate real values once you start on it, rather than shipping with the `.env.example`
placeholder:

```bash
openssl rand -base64 32   # NYVRA_FIELD_ENCRYPTION_KEY
openssl rand -base64 32   # NYVRA_BLIND_INDEX_KEY
```

Same commands for any environment; only the destination differs (`.env` locally, the platform secret
manager everywhere else). See `docs/PREREQUISITES.md` §3 for the first-run version of this.

### 6.3 Key-rotation runbook (stub)

A stub, not a drill — the actual `EncryptedStringConverter`/re-encrypt job this describes doesn't
exist yet (`TODO.md` Phase 2.10). Documenting the intended procedure now so the encryption work is
built against a known rotation story from day one, not bolted on after.

**Field-encryption key (`NYVRA_FIELD_ENCRYPTION_KEY`), dual-key rotation — zero downtime:**
1. Generate a new key (`openssl rand -base64 32`).
2. Set the **current** value into `NYVRA_FIELD_ENCRYPTION_KEY_PREVIOUS`; set the newly generated value
   into `NYVRA_FIELD_ENCRYPTION_KEY`. The app must be able to decrypt with *either* while both are set
   (reads try current, fall back to previous) and always encrypt new/updated rows with current.
3. Deploy — from this point every write uses the new key; every existing 🔒 row is still readable via
   `_PREVIOUS`.
4. Run the background re-encrypt job (`TODO.md` Phase 2.10) to walk every 🔒 row and rewrite it under
   the current key. Safe to run gradually — the app tolerates both keys throughout.
5. Once the job reports 100% and a spot-check confirms no row still needs the old key, remove
   `NYVRA_FIELD_ENCRYPTION_KEY_PREVIOUS` and deploy again.
6. Securely destroy the old key material (it must not be needed again after step 5).

**Blind-index key (`NYVRA_BLIND_INDEX_KEY`) rotation is *not* zero-downtime the same way**: every
blind-index value (e.g. `email_hash`) is a deterministic HMAC of the plaintext under this key, so
rotating it means recomputing every hash in one pass before old-key lookups stop working — plan a
maintenance window or a similar dual-column expand/contract, don't reuse the field-encryption
procedure as-is.

**All other secrets** (DB/Redis/RabbitMQ/S3 credentials, OAuth client secrets, API keys): rotate via
the platform's secret manager, then a rolling restart — no application-level dual-key handling needed
since these aren't used to decrypt already-persisted data.

### 6.4 Secret scanning

CI runs `gitleaks` (`.github/workflows/ci.yml`) against the full commit history on every PR and push
to `main`, failing the build if it finds anything matching a secret pattern. `.gitleaks.toml`
allowlists `docker-compose.yml`'s local-only placeholder credentials (`nyvra`/`nyvra`, `guest`/`guest`,
etc.) — those are intentionally trivial dev-only values, not a leak.

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
