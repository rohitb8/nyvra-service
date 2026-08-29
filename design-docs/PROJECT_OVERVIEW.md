# PROJECT_OVERVIEW.md — nyvra

Vision, scope, and the foundational decisions for the nyvra platform. This is the canonical home for
the material that was in the original planning doc (`Personal Finance App Plan.pdf`), now decomposed
into the design docs in this folder and in the `nyvra-ui` repo.

---

## 1. Vision

A personal-finance **"accountant"** for the Indian market. nyvra pulls together a person's whole
financial picture — bank accounts, investments, income, expenses, loans — mostly through the **RBI
Account Aggregator (AA)** framework, and answers one question on the homepage:

> **"Am I in control of my money?"**

as a single **Financial Health Score (0–100)**, backed by clear, honest breakdowns (net worth,
spending habits, portfolio allocation vs targets, trends) and specific, non-judgmental insights.

**Audience:** individuals in India (salaried and self-employed). Solo-founder product.

**Product principle:** every number is explainable and every number cites its source and freshness.
The "brain" (financial rules + health score) is the differentiator — see `FINANCIAL_RULES.md` and
`HEALTH_SCORE_SPEC.md`.

---

## 2. Goals

1. One-glance answer to "am I in control", trustworthy enough to act on.
2. Low-effort data: connect via AA once, data stays current; minimal manual entry.
3. Correct financial math — emphasised in tests (`TEST_STRATEGY.md`, planned).
4. Privacy and compliance by design (DPDP Act 2023) — data minimisation, consent, erasure.
5. Scales to hundreds of thousands of users without a rewrite.

---

## 3. Scope

### In scope (v1)
- AA-based ingestion of accounts, balances, transactions, holdings (Setu / Finvu / OneMoney as the AA).
- Manual entry for what AA can't cover (physical assets, some liabilities, goals).
- Net worth (assets − liabilities) with history.
- Spending categorisation + spending-habit percentage breakdowns.
- Portfolio holdings, allocation vs age/risk targets, drift alerts, XIRR.
- Income modelling (salary, recurring, irregular) + payslip upload.
- Financial Health Score + insights.
- Goals (target amount + date) and progress.
- User profile, preferences (risk band), consent management, data export & account deletion.

### Explicitly out of scope (v1)
- Tax computation or filing.
- Any movement of money — bill pay, transfers, investing, trading.
- Robo-advisory / personalised regulated investment advice.
- Multi-currency accounting (foreign holdings are valued and shown in INR; FX is display-only).
- Shared / household / family budgets; multi-user accounts.
- Native mobile apps (responsive web only in v1).
- Email/SMS scraping as a primary source (Gmail API is a *supplement* only).

---

## 4. The three locked architectural decisions

Settled up front; changing any requires an ADR.

### 4.1 Never store raw card numbers or bank credentials
Storing full PANs puts the project in PCI-DSS scope — untenable for a solo build. Store only: card
**last 4**, network, and a user label. For bank accounts: **masked identifiers only**, never
credentials. (Enforced in `DATABASE_DESIGN.md`.)

### 4.2 Use the RBI Account Aggregator framework, not scraping
In the Indian ecosystem (Kite/Zerodha, EPF, NPS), the legitimate, stable way to fetch financial data
is the **RBI AA framework** via a licensed AA (Setu, Finvu, OneMoney). Email/SMS parsing is fragile
and breaks constantly — the Gmail API is only a supplement for hints. Plan for **DPDP Act 2023** from
day one. (See `INTEGRATIONS.md` and `COMPLIANCE.md`, planned.)

### 4.3 Modular monolith, not microservices
A **modular monolith** (Spring Modulith) on PostgreSQL scales to hundreds of thousands of users.
Microservices would add operational complexity that slows a solo founder down. Scale **horizontally**
later by keeping the app stateless; split a module into a service only if a real bottleneck forces it.

---

## 5. Region & compliance context

- **India-specific:** INR; Account Aggregator; EPF / NPS; broker APIs (Kite); lakh/crore number
  formatting decision pending in `CONTENT_STYLE_GUIDE.md`.
- **DPDP Act 2023:** lawful consent per purpose, data minimisation, retention limits, the right to
  erasure and to data portability. Consent records are append-only (`DOMAIN_MODEL.md` → User context).
- **Data residency:** keep data in an India region (see `ENVIRONMENTS.md` §7).
- Detailed obligations and the data-processing register live in `COMPLIANCE.md` (planned, P1).

---

## 6. How it scales

- **Stateless app servers** behind a load balancer → horizontal scaling.
- **PostgreSQL** primary with read replicas; **TimescaleDB** for time-series (portfolio value, price
  history, score history); native partitioning for `transaction` / `expense`.
- **Redis** for computed dashboards, NAV/FX caches, AA session state.
- **RabbitMQ** for the async ingestion pipeline (retries + backpressure).
- **Object storage** (S3-compatible) for documents.
- The real bottleneck is **third-party API rate limits and ingestion reliability**, not compute —
  design for retries and backpressure, not raw throughput. (See `EVENT_DESIGN.md`, planned.)

---

## 7. Roadmap / suggested build order

1. **Specs first (done / in progress):** this folder's P0 set — `DOMAIN_MODEL`, `DATABASE_DESIGN`,
   `FINANCIAL_RULES`, `HEALTH_SCORE_SPEC`, `ENVIRONMENTS`, `TECH_STACK`.
2. **Scaffold** both repos (Spring Modulith backend, Angular frontend) — done.
3. **Auth + user** end to end: Keycloak realm, resource-server config, JIT `UserProfile` provisioning.
4. **One data source end to end:** AA consent → fetch → normalise → categorise → persist, for
   deposit accounts + transactions. Prove the pipeline before adding FI types.
5. **Health-score engine** against `HEALTH_SCORE_SPEC.md`, with tests proving each sub-score and the
   worked example.
6. **Dashboard** (see `nyvra-ui` → `DASHBOARD_SPEC.md`, planned) reading real data.
7. Broaden: portfolio valuation + price feed, more FI types, insights, goals.
8. Harden: `SECURITY.md`, `COMPLIANCE.md`, `OBSERVABILITY.md`, `CI_CD.md`, perf.

---

## 8. Related documents

| Where | Doc | For |
|---|---|---|
| this folder | `CLAUDE.md` | Index, stack table, auth model, conventions |
| this folder | `TECH_STACK.md` | Versions + rationale for every component |
| this folder | `DOMAIN_MODEL.md` / `DATABASE_DESIGN.md` | Domain and schema |
| this folder | `FINANCIAL_RULES.md` / `HEALTH_SCORE_SPEC.md` | The product's brain |
| this folder | `ENVIRONMENTS.md` | Profiles, config, secrets, promotion |
| `nyvra-ui` repo | `design-docs/*` | Frontend architecture, UX, and the design backlog |
| planned | `GLOSSARY.md` | Shared domain vocabulary (NAV, XIRR, corpus, …) |
