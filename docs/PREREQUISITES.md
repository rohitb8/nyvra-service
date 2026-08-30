# PREREQUISITES.md — nyvra-backend

What to have installed and set up, and what to read, before writing code against this repo. Split
into what you need **today** (local dev) and what you'll need **later** (by the `TODO.md` phase that
first needs it) so you're not blocked chasing an account you don't need yet.

---

## 1. Local tooling (needed now)

| Tool | Notes |
|---|---|
| JDK 21 | LTS baseline (`docs/engineering/TECH_STACK.md`). No separate Maven install needed — use `./mvnw`. |
| Docker + Docker Compose v2 | Runs the whole local stack: Postgres/Timescale, Redis, RabbitMQ, MinIO, Keycloak. |
| Git | |

That's it to get a working local backend. Nothing else is required — `docker compose up -d` brings up
every dependency with defaults that already match `application-local.yml` / `.env.example`. No sandbox
accounts, no cloud provider, no external API keys are needed for local development.

---

## 2. First-run checklist

```bash
./scripts/dev-up.sh
```
Starts the Docker daemon if needed, brings up the compose stack, waits for it to report healthy
(including Keycloak's realm import, which has no Compose-level healthcheck), then runs the app on
`:8080` (profile `local`; Flyway `V1` applies on boot). `./scripts/dev-up.sh --infra-only` brings up
just the stack without starting the app. Equivalent manual steps:

```bash
cp .env.example .env            # defaults already match the compose stack; edit only to override
docker compose up -d            # Postgres/Timescale, Redis, RabbitMQ, MinIO, Keycloak (+ its DB)
./mvnw spring-boot:run          # profile 'local'; Flyway V1 applies on boot
```

Verify:
- [ ] `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`
- [ ] Swagger UI loads at `http://localhost:8080/swagger-ui.html`
- [ ] `GET /api/v1/users/me` with a Keycloak-issued bearer token (user `demo`/`demo` on the local
      realm, `http://localhost:8081`) returns 200 and provisions a `user_profile` row

This mirrors `TODO.md` §1.1 ("Verify the local stack end-to-end") — that section owns the definitive,
up-to-date version of this checklist; treat it as canonical if the two ever drift.

---

## 3. Secrets to generate before touching encrypted columns

Not needed to boot the app today (field-level encryption is `TODO.md` §2.10, not yet implemented), but
generate these once you start on it so `.env` never carries the placeholder value:

```bash
openssl rand -base64 32   # NYVRA_FIELD_ENCRYPTION_KEY
openssl rand -base64 32   # NYVRA_BLIND_INDEX_KEY (HMAC key for email_hash-style lookups)
```

Never commit real values — `.env` is git-ignored, `.env.example` keeps placeholders only. See
`docs/operations/ENVIRONMENTS.md` §6 for the full secrets inventory and rotation notes.

---

## 4. External accounts needed later, by phase

Nothing below blocks local development. Each becomes a prerequisite only when you reach the `TODO.md`
phase named:

| Need it for | Account / credential | `TODO.md` phase |
|---|---|---|
| Ingestion pipeline | AA sandbox account (Setu, Finvu, or **OneMoney** — pick one; provider choice is still open) | Phase 5.3 |
| Portfolio valuation / price feed | A price/NAV feed API key (AMFI NAV file is free for MFs; an equities provider still to be chosen) | Phase 4.5 / 5.7 |
| Gmail supplement (optional, low priority) | Gmail API OAuth client | Phase 5.8 |
| Error tracking | Sentry DSN | Phase 7.3 |
| Shared dev/staging/prod deploys | GHCR (or chosen registry) access + a domain with DNS control | `docs/operations/DEV_DEPLOYMENT_PLAN.md`, Phase 7.5 |
| Cloud provisioning | A cloud provider account (AWS/GCP/Azure — still to be chosen) | Phase 7.6, `docs/operations/ENVIRONMENTS.md` §7 |

---

## 5. Reading order before writing code

Follow the root `CLAUDE.md` "Which design doc to read" table for task-specific docs. As a baseline:

1. **Always first:** `docs/product/PROJECT_OVERVIEW.md` (vision, scope, the three locked decisions) and
   `docs/product/DOMAIN_MODEL.md` (bounded contexts — the map every module follows).
2. **Before any migration or entity:** `docs/engineering/DATABASE_DESIGN.md`.
3. **Before touching `analytics` or any rule/score logic:** `docs/product/FINANCIAL_RULES.md` and
   `docs/product/HEALTH_SCORE_SPEC.md` — implement these exactly, don't invent logic.
4. **Before any Java PR:** `docs/engineering/CODE_STYLE.md`.
5. **Before config, profiles, or deploy work:** `docs/operations/ENVIRONMENTS.md`.

---

## 6. Open decisions that block later phases

Flagged here so nothing gets built on an assumption that's still unsettled. Each is owned by a
`TODO.md` task — resolve it there, not ad hoc in a PR:

- **AA provider** (Setu / Finvu / OneMoney) — `TODO.md` Phase 5.3.
- **Cloud provider** (AWS / GCP / Azure) — `docs/operations/ENVIRONMENTS.md` §7, `TODO.md` Phase 7.6.
- **Pagination style** for high-volume lists (`transaction`, `expense`) — page-based vs cursor-based,
  or both — `TODO.md` Phase 3.1.
- **Partition management** for the monthly-partitioned `transaction`/`expense` tables — `pg_partman` vs
  a scheduled job vs rolling migrations — `TODO.md` Phase 2.2.
