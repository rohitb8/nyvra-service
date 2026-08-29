# nyvra-service
Backend for **nyvra** — a personal-finance "accountant" for the Indian market. Java 21 · Spring Boot 3.3 ·
Spring Modulith (modular monolith) · PostgreSQL + TimescaleDB · Flyway · OAuth2 resource server (Keycloak).

> Design docs live in [`design-docs/`](design-docs/). Start with
> [`design-docs/CLAUDE.md`](design-docs/CLAUDE.md) and
> [`design-docs/PROJECT_OVERVIEW.md`](design-docs/PROJECT_OVERVIEW.md).

## Prerequisites

- JDK 21
- Docker + Docker Compose

## Run locally

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

Smoke test (needs a token from Keycloak for user `demo` / `demo`):

```bash
curl -s http://localhost:8080/api/v1/users/me -H "Authorization: Bearer $TOKEN"
```

## Build / test

```bash
./mvnw clean verify        # compile + tests
./mvnw spring-boot:build-image   # OCI image (or use the Dockerfile)
```

## Environments

Spring profiles: `local`, `dev`, `staging`, `prod` (+ `test` for the test suite).
Config strategy, secrets, and the promotion flow are in
[`design-docs/ENVIRONMENTS.md`](design-docs/ENVIRONMENTS.md). No secrets in the repo or in
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
