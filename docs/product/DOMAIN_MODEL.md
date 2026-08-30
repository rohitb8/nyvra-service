# DOMAIN_MODEL.md — nyvra-backend

Domain-driven view of the system: bounded contexts, the aggregates inside each, key invariants,
and how the contexts talk to each other. This is the map the Spring Modulith module boundaries
should follow.

Ubiquitous language terms (NAV, XIRR, corpus, allocation, emergency fund, essential vs discretionary
spend, snapshot) are defined in the shared `GLOSSARY.md` (planned).

---

## Context map

```
                         ┌─────────────────┐
                         │   Identity      │  (Keycloak — external IdP)
                         └────────┬────────┘
                                  │ sub (subject)
                         ┌────────▼────────┐
                         │      User       │  profile, preferences, risk band, DOB
                         └───┬────┬────┬───┘
        ┌────────────────────┘    │    └────────────────────┐
        │                         │                         │
┌───────▼───────┐        ┌────────▼────────┐        ┌────────▼────────┐
│   Ingestion   │───────▶│    Accounts     │        │     Income      │
│ (AA consent,  │  raw   │ bank/loan/card  │        │ sources, salary │
│  fetch, norm) │  feed  │  balances, txns │        │  recurring      │
└───────┬───────┘        └───┬─────────┬───┘        └────────┬────────┘
        │                    │         │                     │
        │            ┌───────▼───┐ ┌───▼──────────┐          │
        │            │ Expenses  │ │  Portfolio   │          │
        │            │ categorise│ │ holdings,    │          │
        │            │ habits %  │ │ valuations   │          │
        │            └─────┬─────┘ └──────┬───────┘          │
        │                  │              │                  │
        │            ┌─────▼──────────────▼──────────────────▼─────┐
        │            │                 Net Worth                    │
        │            │        assets − liabilities, snapshots       │
        │            └───────────────────┬──────────────────────────┘
        │                                │
        │                     ┌──────────▼──────────┐
        └────────────────────▶│      Analytics      │  trends, breakdowns, dashboard summary
                              │  + Health Score      │  (FINANCIAL_RULES, HEALTH_SCORE_SPEC)
                              └─────────────────────┘
```

Relationship types:
- **Ingestion → Accounts / Portfolio / Income / Expenses:** upstream/downstream. Ingestion publishes
  normalised events; downstream contexts own their model and translate.
- **Accounts/Portfolio/Income/Expenses → Net Worth:** downstream. Net Worth subscribes to balance/valuation changes.
- **Everything → Analytics + Health Score:** downstream, read-mostly. Analytics never writes back to source contexts.
- **User** is a shared kernel for `user_id`, `date_of_birth` (→ age-based rules), `risk_band`, `base_currency`, and preferences.

Cross-context communication is **event-driven via RabbitMQ** for anything ingestion-triggered, and
direct read-model queries (within the monolith, module-to-module API) for synchronous dashboard reads.

---

## 1. User context

**Purpose:** who the user is, plus the inputs that financial rules need about them.

| Aggregate | Key fields | Invariants |
|---|---|---|
| `UserProfile` (root) | `id` (UUID), `keycloakSubject` (unique), `email`, `displayName`, `dateOfBirth`, `baseCurrency` (default `INR`), `createdAt`, `dpdpConsentAt` | `keycloakSubject` immutable once set; `baseCurrency` is display/reporting currency, cannot change after first snapshot |
| `UserPreferences` | `riskBand` (`CONSERVATIVE|BALANCED|AGGRESSIVE`), `emergencyFundMonthsTarget` (override, default from rules), `dashboardLayout`, `notificationChannels` | `riskBand` drives allocation targets in `FINANCIAL_RULES.md` |
| `DataConsentRecord` | `id`, `purpose`, `scope`, `grantedAt`, `expiresAt`, `revokedAt`, `source` (`AA|GMAIL|MANUAL`) | append-only; never hard-deleted (DPDP audit) |

Publishes: `UserRegistered`, `UserProfileUpdated`, `ConsentRevoked`.

---

## 2. Ingestion context

**Purpose:** own everything RBI Account-Aggregator-specific and every other raw feed, and emit a
clean normalised stream. Nothing downstream depends on AA SDK types.

| Aggregate | Key fields | Invariants |
|---|---|---|
| `AggregatorConsent` (root) | `id`, `userId`, `aaHandle`, `fipList`, `consentHandle`, `consentId`, `status` (`REQUESTED|ACTIVE|PAUSED|REVOKED|EXPIRED`), `dataRange`, `frequency`, `expiresAt` | State machine: `REQUESTED→ACTIVE→(PAUSED↔ACTIVE)→REVOKED|EXPIRED`; no data fetch unless `ACTIVE` |
| `FetchSession` | `id`, `consentId`, `sessionId`, `requestedAt`, `completedAt`, `status`, `fiTypes`, `error` | Idempotent per `(consentId, dataRange)`; retried with backoff |
| `RawFinancialRecord` | `id`, `fetchSessionId`, `fipId`, `accountRef` (masked), `payloadType` (`DEPOSIT|TERM_DEPOSIT|RECURRING_DEPOSIT|MUTUAL_FUND|EQUITIES|NPS|EPF|LOAN|CREDIT_CARD`), `rawJson` (encrypted), `receivedAt` | Retained only as long as needed for normalisation + dispute window, then purged per `COMPLIANCE.md` |
| `NormalisationRun` | `id`, `fetchSessionId`, `producedEvents`, `unmapped` | Deterministic; re-runnable without duplicating downstream state (dedup key on event) |

Consumes: `ConsentRevoked` (→ pause fetches).
Publishes: `AccountDiscovered`, `TransactionsIngested`, `HoldingsIngested`, `BalanceUpdated`,
`IncomeCreditDetected`, `IngestionFailed`. All carry a stable `dedupKey`.

Gmail supplement: `EmailDerivedHint` (low-trust) — never creates transactions directly, only
proposes categorisation/merchant hints for the Expenses context to accept or ignore.

---

## 3. Accounts context

**Purpose:** the user's bank, loan, and card accounts and their transaction ledger.

| Aggregate | Key fields | Invariants |
|---|---|---|
| `FinancialAccount` (root) | `id`, `userId`, `type` (`SAVINGS|CURRENT|LOAN|CREDIT_CARD|TERM_DEPOSIT|RECURRING_DEPOSIT|EPF|NPS`), `institution`, `maskedNumber`, `label`, `currency`, `currentBalance`, `balanceAsOf`, `source` (`AA|MANUAL`), `status` (`ACTIVE|CLOSED|STALE`) | Never store full account number or credentials; `maskedNumber` only. `currentBalance` always has a `balanceAsOf` timestamp |
| `Transaction` | `id`, `accountId`, `bookingDate`, `valueDate`, `amount` (signed), `direction` (`DEBIT|CREDIT`), `narration`, `counterparty`, `balanceAfter`, `dedupKey` (unique), `source` | Immutable once ingested; corrections are new reversing transactions. `dedupKey = hash(accountRef, valueDate, amount, narration)` |
| `CardDetail` | `last4`, `network` (`VISA|MASTERCARD|RUPAY|AMEX`), `label`, `creditLimit` | No PAN, no CVV, no expiry beyond month/year label |

Consumes: `AccountDiscovered`, `TransactionsIngested`, `BalanceUpdated`.
Publishes: `TransactionRecorded`, `AccountBalanceChanged`, `LiabilityBalanceChanged`.

---

## 4. Income context

**Purpose:** model recurring and one-off income so rules can compute income-based ratios.

| Aggregate | Key fields | Invariants |
|---|---|---|
| `IncomeSource` (root) | `id`, `userId`, `name`, `type` (`SALARY|BUSINESS|RENTAL|INTEREST|DIVIDEND|CAPITAL_GAIN|OTHER`), `cadence` (`MONTHLY|QUARTERLY|ANNUAL|IRREGULAR`), `expectedAmount`, `currency`, `active` | `expectedAmount` required unless `cadence = IRREGULAR` |
| `IncomeEntry` | `id`, `sourceId`, `periodStart`, `periodEnd`, `grossAmount`, `netAmount`, `receivedOn`, `linkedTransactionId?`, `origin` (`AA_DETECTED|MANUAL|PAYSLIP`) | `netAmount ≤ grossAmount`; period cannot overlap another entry for the same source |
| `PayslipDocument` | `id`, `incomeEntryId`, `objectKey`, `parsedFields` | Document body in object storage, not DB |

Consumes: `IncomeCreditDetected` (→ propose `IncomeEntry`).
Publishes: `IncomeRecorded`, `MonthlyIncomeRecalculated` (rolling 3/6/12-month averages).

---

## 5. Expenses context

**Purpose:** categorise spending and produce the spending-habit percentage breakdown.

| Aggregate | Key fields | Invariants |
|---|---|---|
| `Expense` (root) | `id`, `userId`, `transactionId?`, `date`, `amount`, `currency`, `categoryId`, `subcategoryId?`, `merchant`, `necessity` (`ESSENTIAL|DISCRETIONARY|DEBT_REPAYMENT|SAVINGS_TRANSFER`), `origin` (`AA|MANUAL|SPLIT`), `excludedFromHabits` (bool) | Sum of split children = parent amount; `SAVINGS_TRANSFER` excluded from "spending" totals but tracked for savings rate |
| `Category` | `id`, `name`, `parentId?`, `necessityDefault`, `system` (bool) | System categories are seeded and immutable; users add custom children only |
| `CategorisationRule` | `id`, `userId?`, `matcher` (merchant/narration regex or MCC), `categoryId`, `necessity`, `priority` | User rules override system rules; higher `priority` wins |
| `SpendingHabitSnapshot` | `id`, `userId`, `period` (month), `byCategoryPct`, `essentialPct`, `discretionaryPct`, `totalSpend` | Recomputed on any expense change in the period; percentages sum to 100 (±rounding) |

Consumes: `TransactionRecorded`, `EmailDerivedHint`.
Publishes: `ExpenseCategorised`, `SpendingHabitsRecalculated`.

---

## 6. Portfolio context

**Purpose:** holdings and valuations for equities, mutual funds, NPS/EPF, and foreign investments.

| Aggregate | Key fields | Invariants |
|---|---|---|
| `PortfolioHolding` (root) | `id`, `userId`, `instrumentId`, `assetClass` (`EQUITY|MUTUAL_FUND|BOND|NPS|EPF|FOREIGN_EQUITY|CASH|GOLD|REIT`), `quantity`, `avgCost`, `currency`, `accountId?`, `source` | `quantity ≥ 0`; a holding with `quantity = 0` is closed, kept for XIRR history |
| `Instrument` | `id`, `isin?`, `symbol`, `name`, `assetClass`, `currency`, `country` | ISIN unique when present |
| `PriceQuote` (time-series) | `instrumentId`, `asOf`, `price`, `source` | Timescale hypertable; latest quote drives current valuation |
| `ValuationSnapshot` (time-series) | `userId`, `asOf`, `byAssetClass`, `totalValue`, `investedValue`, `unrealisedGain`, `xirr` | Timescale hypertable; one per user per day minimum |
| `CorporateAction` | `instrumentId`, `type` (`SPLIT|BONUS|DIVIDEND|MERGER`), `exDate`, `ratio` | Adjusts `quantity`/`avgCost` deterministically |

Consumes: `HoldingsIngested`, external price/NAV feed events.
Publishes: `HoldingsChanged`, `PortfolioValued`, `AllocationDrifted` (when bands in `FINANCIAL_RULES.md` are breached).

---

## 7. Net Worth context

**Purpose:** single source of truth for assets − liabilities and its trend.

| Aggregate | Key fields | Invariants |
|---|---|---|
| `NetWorthSnapshot` (time-series, root) | `id`, `userId`, `asOf`, `totalAssets`, `totalLiabilities`, `netWorth`, `breakdown` (by asset/liability class), `contributingSources` | `netWorth = totalAssets − totalLiabilities`; immutable once written; at most one per `(userId, asOf date)` |
| `ManualAssetLiability` | `id`, `userId`, `kind` (`ASSET|LIABILITY`), `class` (`REAL_ESTATE|VEHICLE|JEWELLERY|PRIVATE_EQUITY|PERSONAL_LOAN|OTHER`), `label`, `value`, `valueAsOf`, `revaluationCadence` | User-maintained; flagged `STALE` when `valueAsOf` older than cadence |

Consumes: `AccountBalanceChanged`, `LiabilityBalanceChanged`, `PortfolioValued`, `ManualAssetLiability` changes.
Publishes: `NetWorthSnapshotCreated`.

Snapshot policy: event-driven recompute, debounced; plus a guaranteed daily snapshot job so trend
lines have no gaps.

---

## 8. Analytics + Health Score context

**Purpose:** read-only derivations for the dashboard, and the health score. Owns no source data.

| Read model / aggregate | Key fields |
|---|---|
| `DashboardSummary` | `netWorth`, `netWorthChange30d`, `monthlyIncomeAvg`, `monthlySpendAvg`, `savingsRate`, `emergencyFundMonths`, `topCategories`, `allocationVsTarget`, `healthScore` |
| `TrendSeries` | `metric`, `granularity` (`DAY|WEEK|MONTH`), `points[]` — backed by Timescale continuous aggregates |
| `HealthScore` (time-series) | `userId`, `asOf`, `overall` (0–100), `band`, `subScores{savings, spendingControl, emergencyFund, diversification, debt, goalProgress}`, `confidence`, `inputsHash` |
| `Insight` | `id`, `userId`, `ruleId`, `severity`, `message`, `evidence`, `dismissedAt?` |

Consumes: everything above.
Publishes: `HealthScoreComputed`, `InsightRaised`.
All formulas defined in `FINANCIAL_RULES.md` and `HEALTH_SCORE_SPEC.md` — this context implements, does not invent.

---

## Cross-cutting rules

- **Idempotency:** every event carries `dedupKey`; every consumer upserts on it. Re-running ingestion must not double-count.
- **Provenance:** every user-visible number can name its `source` (`AA`, `MANUAL`, `DERIVED`) and `asOf`.
- **Soft delete + DPDP erasure:** normal deletes are soft (`deletedAt`); a DPDP erasure request triggers a
  documented hard-purge workflow across contexts (see `COMPLIANCE.md`, planned).
- **Money type:** `BigDecimal` everywhere, currency code attached. FX conversion only in Analytics for display.
- **No context reaches into another's tables.** Cross-module reads go through the owning module's API; cross-module writes go through events.
