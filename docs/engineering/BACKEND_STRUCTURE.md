# nyvra-backend — Repository Structure

**Stack:** Java 21, Spring Boot 3.3.x, PostgreSQL + TimescaleDB, Flyway, Spring Security (OAuth2 **resource server**, OIDC via Keycloak), springdoc-openapi
**Architecture:** Modular monolith
**Package root:** `com.rohit.nyvra`

> **Auth note:** the JWT/refresh-token sections below are **superseded** — nyvra-backend no longer
> issues or refreshes tokens. Keycloak is the identity provider; the backend only validates access
> tokens. See `CLAUDE.md` → "Auth model" and `ENVIRONMENTS.md` §5. The rest of this doc stands.

---

## Top-level layout

```
nyvra-backend/
├── src/
│   ├── main/
│   │   ├── java/com/rohit/nyvra/
│   │   │   ├── NyvraApplication.java
│   │   │   ├── config/
│   │   │   ├── security/
│   │   │   ├── controller/
│   │   │   ├── service/
│   │   │   ├── repository/
│   │   │   ├── model/
│   │   │   ├── dto/
│   │   │   └── exception/
│   │   └── resources/
│   │       ├── application.yml           # profile-independent defaults, no secrets
│   │       ├── application-local.yml
│   │       ├── application-dev.yml
│   │       ├── application-staging.yml
│   │       ├── application-prod.yml
│   │       ├── db/migration/             # Flyway SQL migrations (V1__init_schema.sql, etc.)
│   │       └── openapi/
│   │           └── nyvra-api-v1.yaml
│   └── test/
│       ├── java/com/rohit/nyvra/
│       └── resources/application-test.yml
├── keycloak/
│   └── realm-nyvra.json                  # version-controlled realm (imported per env)
├── docker-compose.yml                    # Postgres/Timescale, Redis, RabbitMQ, MinIO, Keycloak
├── Dockerfile
├── .dockerignore
├── .env.example
├── pom.xml
├── mvnw / mvnw.cmd / .mvn/
├── .gitignore
└── README.md
```

---

## Module breakdown (modular monolith)

Each domain module below should eventually get its own sub-package under `controller/`, `service/`, `repository/`, `model/`, `dto/` — e.g. `controller/income/`, `service/income/`. Keeps the monolith modular without splitting into microservices prematurely.

| Module | Responsibility |
|---|---|
| `auth` | OIDC integration surface: JWT validation config, `sub`→`UserProfile` provisioning on first call, role mapping. **No** login/signup/refresh endpoints (Keycloak owns those) |
| `income` | Income sources, salary, recurring income entries |
| `expenses` | Expense entries, categorization, spending-habit analytics |
| `networth` | Aggregates assets − liabilities over time, snapshots |
| `portfolio` | Stocks, mutual funds, foreign investments — holdings and valuations |
| `aggregator` | RBI Account Aggregator integration — consent flow, data pull, normalization |
| `analytics` | Spending percentage breakdowns, trend calculations, dashboard summary endpoints |
| `user` | User profile, preferences, settings |

---

## Key files and their purpose

### `NyvraApplication.java`
Standard Spring Boot entry point (`@SpringBootApplication`).

### `config/`
- `SecurityConfig.java` — `SecurityFilterChain` bean, stateless session policy (`SessionCreationPolicy.STATELESS`), `oauth2ResourceServer().jwt(...)` with the Keycloak issuer, CORS config for the Angular frontend origin, method-security enabled
- `OpenApiConfig.java` — springdoc metadata (title, version, description) surfaced at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`; OAuth2 security scheme pointed at the Keycloak realm so "Authorize" works in Swagger
- `JacksonConfig.java` — date/time serialization, null handling defaults

### `security/`
- Uses `spring-boot-starter-oauth2-resource-server` — no custom token code.
- `JwtConfig` / `SecurityConfig` — `JwtDecoder` from `spring.security.oauth2.resourceserver.jwt.issuer-uri`, audience validation, and a `JwtAuthenticationConverter` mapping Keycloak `realm_access.roles` → `ROLE_*` authorities.
- `CurrentUserService` — resolves the authenticated `sub` to a `UserProfile`, creating one on first request (just-in-time provisioning). Never stores credentials.
- Token lifetimes (access ~15 min, refresh rotating) are configured in Keycloak, not here.

### `controller/`
REST controllers, one per module (e.g. `IncomeController`, `ExpenseController`, `PortfolioController`, `NetWorthController`, `AnalyticsController`, `UserController`). All routes prefixed `/api/v1/...`. There is no `AuthController` — authentication happens at Keycloak.

### `service/`
Business logic layer. Controllers stay thin; all calculation logic (net worth aggregation, spending percentage breakdowns) lives here.

### `repository/`
Spring Data JPA repositories, one per entity.

### `model/`
JPA entities — `User`, `IncomeEntry`, `Expense`, `Asset`, `Liability`, `PortfolioHolding`, `AggregatorConsent`, etc.

### `dto/`
Request/response DTOs — never expose JPA entities directly over the API. Use MapStruct for entity↔DTO mapping.

### `exception/`
- `GlobalExceptionHandler.java` — `@RestControllerAdvice` for consistent error responses
- Custom exceptions: `ResourceNotFoundException`, `InvalidConsentException`, etc.

### `resources/db/migration/`
Flyway-managed SQL migrations. Naming convention: `V1__init_schema.sql`, `V2__add_portfolio_tables.sql`, etc. Never edit an already-applied migration — always add a new one.

### `resources/openapi/nyvra-api-v1.yaml`
The API contract — source of truth for the frontend's generated TypeScript client. Kept in sync automatically via springdoc (generated from annotated controllers) or maintained by hand if you prefer contract-first development.

---

## API versioning
All endpoints under `/api/v1/...`. When breaking changes are needed later, introduce `/api/v2/...` alongside — don't mutate v1 in place while any frontend still depends on it.

## Auth flow summary (OIDC / Keycloak)
1. The Angular SPA runs Authorization Code + PKCE against Keycloak (public client `nyvra-web`) and obtains an access token.
2. SPA sends `Authorization: Bearer <access token>` on every `/api/v1` call.
3. nyvra-backend validates the JWT against the Keycloak realm JWKS (issuer + audience + expiry + signature). No session, no server-side token store.
4. On the first authenticated call for a new `sub`, `CurrentUserService` provisions a `UserProfile` row.
5. Token renewal (refresh-token rotation) is handled by Keycloak + the SPA's OIDC library; the backend is not involved.

## Local dev
```bash
docker compose up -d          # Postgres/Timescale, Redis, RabbitMQ, MinIO, Keycloak (+ its DB)
./mvnw flyway:migrate         # or let the local profile migrate on boot
./mvnw spring-boot:run        # starts backend on :8080, profile 'local'
```
Swagger UI: `http://localhost:8080/swagger-ui.html` · Keycloak: `http://localhost:8081`
See `ENVIRONMENTS.md` for the full local stack and profiles.

## RBI Account Aggregator integration notes
- `aggregator` module should isolate all AA-framework-specific code (consent request/approval, FIU-FIP communication) behind a clean internal interface, so the rest of the app never depends on AA SDK specifics directly — makes it easier to swap/upgrade the AA integration later.
- Store only what's needed post-consent-expiry; follow AA framework's data minimization guidance.
