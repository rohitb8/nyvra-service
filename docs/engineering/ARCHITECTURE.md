# ARCHITECTURE.md — nyvra-backend

System diagram, module dependency rules, sync vs async paths, and the ADR index. Read
`docs/product/DOMAIN_MODEL.md` first — this doc is that context map redrawn as *enforceable* dependency
rules for the Spring Modulith boundaries, not a restatement of the bounded contexts themselves.

---

## 1. System context

Who and what talks to nyvra-backend. `✅` = exists today (skeleton stage); everything else is per
`TODO.md`'s roadmap.

```
        ┌──────────────┐
        │  Angular SPA │  (nyvra-ui, separate repo)
        │  (browser)   │
        └──────┬───────┘
               │ HTTPS, Bearer JWT
               ▼
        ┌──────────────────────────────┐        ┌───────────────┐
        │        nyvra-backend         │◀──────▶│   Keycloak    │ ✅ OIDC provider
        │   Spring Modulith monolith   │  JWKS   │ (self-hosted) │    (issues tokens; backend only
        │            ✅                │         └───────────────┘     validates — see docs/CLAUDE.md)
        └───┬───────┬───────┬───────┬──┘
            │       │       │       │
            ▼       ▼       ▼       ▼
      ┌─────────┐ ┌─────┐ ┌────────┐ ┌──────────────┐
      │ Postgres│ │Redis│ │RabbitMQ│ │ Object storage│
      │+Timescale│ │     │ │        │ │ (S3 / MinIO)  │
      │    ✅    │ │ ✅  │ │   ✅   │ │      ✅       │
      └─────────┘ └─────┘ └────────┘ └──────────────┘

        ┌───────────────┐   ┌──────────────┐   ┌─────────────┐
        │  RBI Account  │   │  Price / NAV │   │  Gmail API  │
        │  Aggregator   │   │  feed        │   │ (supplement,│
        │ (Setu/Finvu/  │   │ (AMFI + an   │   │  optional)  │
        │  OneMoney)    │   │  equities    │   └─────────────┘
        │  — planned    │   │  provider)   │      — planned
        │  (Phase 5)    │   │  — planned   │
        └───────────────┘   └──────────────┘
```

- The app is the **only** thing that talks to Postgres, Redis, RabbitMQ, and object storage — nothing
  else in the system reaches those directly.
- Keycloak is peer infrastructure, not owned by the app: nyvra-backend never issues, refreshes, or
  stores tokens/credentials (`docs/CLAUDE.md` → "Auth model").
- AA provider / price feed / Gmail are outbound-only from the `ingestion` module (planned) — nothing
  downstream of `ingestion` ever talks to them directly (`docs/product/DOMAIN_MODEL.md` §2).

---

## 2. Module map & allowed dependency directions

Same eight contexts as `docs/product/DOMAIN_MODEL.md`'s context map, redrawn as a dependency graph.
Direction matters: an arrow means "may call directly (module API) or consume (events)" — never the
reverse.

```
user  ──────────────────────────────────────────────────────────┐
  ▲                                                              │ shared kernel:
  │ (JIT-provisioned from Keycloak sub; every module reads       │ user_id, DOB, risk_band,
  │  the current user via this module, never re-derives it)      │ base_currency, preferences
  │                                                                ▼
ingestion ──▶ accounts ──▶┐
   │      ──▶ income   ──▶┤
   │      ──▶ portfolio ─▶├──▶ net_worth ──▶ analytics + health_score
   │      ──▶ expenses  ─▶┘        ▲               ▲
   │                               │               │
   └───────────────────────────────┴───────────────┘
     (ingestion never reaches net_worth/analytics directly — always
      through accounts/income/portfolio/expenses)
```

Rules (mechanical, enforceable — see §4 on `ArchitectureTest`):

1. **`ingestion` is upstream of everything, downstream of nothing.** It publishes normalised events
   (`AccountDiscovered`, `TransactionsIngested`, `HoldingsIngested`, `IncomeCreditDetected`, …); no
   other module's code depends on AA SDK types or raw FI payload shapes. `ingestion` → `accounts` /
   `income` / `portfolio` / `expenses` crossings are **events only** (RabbitMQ), never a direct module-API
   call — ingestion runs asynchronously and must not block on downstream module availability.
2. **`accounts` / `income` / `portfolio` / `expenses` are peers.** None of them depends on another. Each
   owns its own tables and publishes change events (`AccountBalanceChanged`, `IncomeRecorded`,
   `HoldingsChanged`, `ExpenseCategorised`, …).
3. **`net_worth` is downstream of the four peers above.** Subscribes to their change events (async,
   debounced recompute) *and* is allowed a direct synchronous module-API read of each (for the
   on-demand "current net worth" endpoint) — this is the one place both crossing types are legitimate
   for the same relationship, because net worth needs both "recompute when something changes" and
   "give me the answer right now."
4. **`analytics` (+ health score) is downstream of everything, upstream of nothing.** Read-only
   derivations; owns no source-of-truth data; never writes back to another module. Implements
   `docs/product/FINANCIAL_RULES.md` / `docs/product/HEALTH_SCORE_SPEC.md` — invents no logic of its
   own. Dashboard reads from `analytics` are synchronous (with a Redis cache in front); score/insight
   recomputation is event-driven + debounced, same pattern as `net_worth`.
5. **`user` is a shared kernel**, not a pipeline stage. Every module may read the current user's
   `user_id`, `date_of_birth`, `risk_band`, `base_currency` — via `user`'s module API, never by
   querying `user_profile`/`user_preferences` tables directly.
6. **No module reaches into another's tables, ever** — not even peers, not even for a read. Cross-module
   reads go through the owning module's public API (a `@Service` or a narrow read-model interface);
   cross-module writes go through events. This is the actual Modulith boundary; §4 is where it starts
   being enforced automatically rather than just documented.

---

## 3. Sync vs async paths

**Synchronous** (in-process method call, same request/response cycle):
- Every `/api/v1/...` read: controller → service → repository, within one module.
- Cross-module reads where the caller needs an answer *now* — `analytics`'s dashboard summary calling
  into `accounts`/`income`/`portfolio`/`expenses`/`net_worth`'s module APIs; `net_worth`'s on-demand
  "current net worth" read.
- Backed by a Redis cache (`dashboard_summary_cache` as the durable fallback) so repeated dashboard
  loads don't re-aggregate every module on every request.

**Asynchronous** (RabbitMQ, decoupled from the request that triggered it):
- The entire `ingestion` pipeline: AA consent → fetch → normalise → publish. Nothing about this can be
  synchronous — it depends on an external provider's timing, must retry/backpressure independently of
  any HTTP request, and a `FetchSession` can span minutes.
- Every downstream reaction to a change event: `net_worth` and `analytics`/`health_score` recomputing
  on `AccountBalanceChanged` / `IncomeRecorded` / `HoldingsChanged` / `ExpenseCategorised` /
  `PortfolioValued`, **debounced 60s per user** (`docs/product/HEALTH_SCORE_SPEC.md` §5) so a burst of
  ingested transactions doesn't trigger a recompute storm.
- Guaranteed daily snapshot jobs (`net_worth`, `health_score`) run on `@Scheduled` + ShedLock
  (leader-only across instances) independent of any event — belt-and-braces so trend lines have no
  gaps even if an event was missed.

Rule of thumb: **if the answer must be correct within the current HTTP request, it's sync; if "correct
within a minute or so" is fine, it's event-driven.** Nothing in the system is async by default — async
is a deliberate choice for the ingestion pipeline and derived-data recomputation specifically.

---

## 4. Enforcement — `ArchitectureTest`

`src/test/java/com/rohit/nyvra/ArchitectureTest.java` currently only asserts the Spring Modulith model
*builds* (every top-level package resolves as a named module) — it does not yet assert §2's dependency
rules. Tightening it to `ApplicationModules.of(NyvraApplication.class).verify()` is blocked on the
module packages actually existing with named interfaces (they're `package-info.java` placeholders
today — `TODO.md` Phase 2+ fills them in per module). Once a module has real classes:
- `verify()` catches any accidental cross-module dependency that isn't going through a module's public
  API — the mechanical version of rule 6 above.
- `@ApplicationModule(allowedDependencies = {...})` on each `package-info.java` encodes rule 1–5's
  specific allowed directions, so a forbidden import fails the build, not just a code review.

This is tracked as a `TODO.md` Phase 1.3/2.x follow-up — noted here so the *intent* is documented even
before the enforcement exists.

---

## 5. ADR index

Full one-file-per-decision ADRs aren't set up yet (no template/convention exists in this repo) — this
index is the lightweight version `TODO.md` Phase 1.4 asks for. Promote an entry to a full ADR doc if a
future decision needs the longer "context / options considered / consequences" format.

| # | Decision | Why (one line) | Reference |
|---|---|---|---|
| 1 | Never store raw card PANs, CVV, or bank credentials | Keeps the system out of PCI-DSS scope entirely | `docs/product/PROJECT_OVERVIEW.md` §4.1 |
| 2 | RBI Account Aggregator framework, not email/SMS scraping, for financial data | AA is the sanctioned, stable path in India; scraping is fragile and breaks constantly | `docs/product/PROJECT_OVERVIEW.md` §4.2 |
| 3 | Modular monolith (Spring Modulith), not microservices | Operational simplicity for a solo founder; scales far enough; module boundaries make a future extraction possible without a rewrite | `docs/product/PROJECT_OVERVIEW.md` §4.3 |
| 4 | Self-hosted Keycloak for auth, not hand-rolled JWT or a managed IdP | Hand-rolled = security-critical code we'd own; managed IdP = per-MAU cost + external dependency in the login path | `docs/CLAUDE.md` → "Auth model", `docs/engineering/TECH_STACK.md` → Identity |
| 5 | Testcontainers Postgres pinned to `timescale/timescaledb-ha:pg16` in tests, not plain `postgres` | Must match prod so hypertable DDL behaves the same in tests as everywhere else | `docs/engineering/CODE_STYLE.md`, `AbstractIntegrationTest.java` |
| 6 | MockMvc `.with(jwt())` for test auth, not a `mock-oauth2-server` container | Simpler, no extra CI container/boot time; sufficient until real issuer/audience validation logic exists to test against a live token | `TestSecurityConfig.java`, `TODO.md` Phase 1.3 / 3.4 |
| 7 | CI validates Flyway via a dedicated `flyway-maven-plugin` run against a Postgres service container, not solely via app-boot side effects | Migrations get an explicit, independent check regardless of whether any test happens to touch the DB | `.github/workflows/ci.yml`, `TODO.md` Phase 1.2 |

---

## 6. Related documents

| Doc | For |
|---|---|
| `docs/product/DOMAIN_MODEL.md` | The bounded contexts and aggregates this doc's module map is derived from |
| `docs/engineering/DATABASE_DESIGN.md` | Schema per context |
| `docs/engineering/TECH_STACK.md` | Every component in §1's diagram, with version + rationale |
| `docs/operations/ENVIRONMENTS.md` | How §1's infrastructure differs per environment |
| `TODO.md` | What's built vs planned, phase by phase |
