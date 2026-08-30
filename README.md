# nyvra-service
Backend for **nyvra** — a personal-finance "accountant" for the Indian market. Java 21 · Spring Boot 3.3 ·
Spring Modulith (modular monolith) · PostgreSQL + TimescaleDB · Flyway · OAuth2 resource server (Keycloak).

> Design docs live in [`docs/`](docs/), grouped into `product/`, `engineering/`, `operations/`. Start
> with [`docs/CLAUDE.md`](docs/CLAUDE.md) and [`docs/product/PROJECT_OVERVIEW.md`](docs/product/PROJECT_OVERVIEW.md).

## Prerequisites

- JDK 21
- Docker + Docker Compose

Full checklist (accounts, secrets, reading order) in [`docs/PREREQUISITES.md`](docs/PREREQUISITES.md).

## Run locally

```bash
./start-local-server.sh
```
Starts Docker if it isn't running, brings up the compose stack, waits for it to be healthy, then
runs the app on `:8080` (profile `local`). `./start-local-server.sh --infra-only` brings up just the
stack without starting the app.

Equivalent manual steps:
```bash
cp .env.example .env                 # adjust if needed; defaults match the compose stack
docker compose up -d                 # Postgres/Timescale, Redis, RabbitMQ, MinIO, Keycloak
./mvnw spring-boot:run               # app on :8080, profile 'local'
```

| Thing | URL |
|---|---|
| API base | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |
| Keycloak | http://localhost:8081 (admin / admin) |
| MinIO console | http://localhost:9001 (nyvra / nyvra-secret) |
| RabbitMQ console | http://localhost:15672 (guest / guest) |

Smoke test — mint a token for `demo`/`demo` (password grant, local-only client) and call the API:

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/realms/nyvra/protocol/openid-connect/token \
  -d "client_id=nyvra-web" -d "grant_type=password" -d "username=demo" -d "password=demo" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

curl -s http://localhost:8080/api/v1/users/me -H "Authorization: Bearer $TOKEN"
```
(`jq -r .access_token` works instead of the `python3` one-liner if you have `jq` installed.) Swap
`demo`/`demo` for `admin`/`admin` to get a token with the `admin` role too.

## Build / test

```bash
./mvnw clean verify        # compile + tests
./mvnw spring-boot:build-image   # OCI image (or use the Dockerfile)
```

## Environments

Spring profiles: `local`, `dev`, `staging`, `prod` (+ `test` for the test suite).
Config strategy, secrets, and the promotion flow are in
[`docs/operations/ENVIRONMENTS.md`](docs/operations/ENVIRONMENTS.md). No secrets in the repo or in
`application-*.yml`.

## Layout

```
src/main/java/com/rohit/nyvra/
├── NyvraApplication.java
├── config/            SecurityConfig, OpenApiConfig, JacksonConfig
├── common/exception/  ApiError, GlobalExceptionHandler, ...
├── user/              first real module — profile, JIT provisioning, GET /users/me
├── ingestion/ accounts/ income/ expense/ networth/ portfolio/ aggregator/ analytics/
│                      module placeholders (package-info only) — build out per DOMAIN_MODEL.md
src/main/resources/
├── application.yml + application-{local,dev,staging,prod}.yml
├── db/migration/      Flyway (V1__init_schema.sql)
└── openapi/           exported contract for the frontend client
```
