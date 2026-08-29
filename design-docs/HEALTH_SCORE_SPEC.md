# HEALTH_SCORE_SPEC.md — nyvra-backend

Exact definition of the homepage **Financial Health Score** (0–100). This must be specified
precisely enough that two independent implementations produce the same number for the same inputs.

Depends on the parameters in `FINANCIAL_RULES.md`. Stamp `ruleset_version` + this doc's
`SCORE_SPEC_VERSION` onto every stored score.

`SCORE_SPEC_VERSION = 2026.1`

---

## 1. Output

```json
{
  "asOf": "2026-08-29T00:00:00Z",
  "overall": 72.4,
  "band": "GOOD",
  "confidence": 0.83,
  "subScores": {
    "savings":         { "value": 65.0, "weight": 0.25, "status": "OK",   "evaluated": true },
    "spendingControl": { "value": 80.0, "weight": 0.15, "status": "OK",   "evaluated": true },
    "emergencyFund":   { "value": 50.0, "weight": 0.20, "status": "WARN", "evaluated": true },
    "debt":            { "value": 90.0, "weight": 0.20, "status": "OK",   "evaluated": true },
    "diversification": { "value": 70.0, "weight": 0.15, "status": "OK",   "evaluated": true },
    "goalProgress":    { "value": 60.0, "weight": 0.05, "status": "OK",   "evaluated": true }
  },
  "topDrivers": ["emergencyFund", "savings"],
  "inputsHash": "sha256:..."
}
```

- `overall` and each sub-score: `NUMERIC`, one decimal, range `[0, 100]`.
- `topDrivers` = the (up to 3) evaluated sub-scores with the largest `weight * (100 − value)` — i.e. where improvement moves the needle most.

---

## 2. Bands

| Band | Range | Meaning |
|---|---|---|
| `NEEDS_ATTENTION` | `0 – 39` | One or more fundamentals broken |
| `FAIR` | `40 – 59` | Getting by, clear gaps |
| `GOOD` | `60 – 79` | In control, room to optimise |
| `EXCELLENT` | `80 – 100` | Strong across the board |

**Hard caps (override the computed band):**
- `emergency_fund_months < emergency.months_min` → `overall` capped at **45**.
- Any `CRITICAL` insight from high-interest debt or fixed-obligations-over-ceiling → `overall` capped at **55**.
- Negative savings rate over the window → `overall` capped at **40**.
Caps apply after weighting; the lowest applicable cap wins. The cap reason is added to `topDrivers`.

---

## 3. Sub-scores

Each sub-score is `0–100`, computed from a **piecewise-linear mapping** of a driving metric.
Notation: `ramp(x, lo, hi)` = `clamp((x − lo) / (hi − lo), 0, 1) * 100`. `invramp(x, lo, hi)` = `100 − ramp(x, lo, hi)`.

### 3.1 `savings` — weight 0.25
Driver: `savings_rate` (§1.1 of FINANCIAL_RULES).
```
savings_score = ramp(savings_rate, savings.min_acceptable, savings.stretch_rate)
             // 0.10 → 0 ; 0.20 → 50 ; 0.30 → 100
```
`status`: `OK` if `savings_rate ≥ savings.target_rate`, `WARN` if `≥ min_acceptable`, `CRITICAL` if below.

### 3.2 `spendingControl` — weight 0.15
Two components, averaged:
```
essential_component     = invramp(essential_ratio,     0.50, 0.75)   // ≤0.50 →100 ; ≥0.75 →0
discretionary_component = invramp(discretionary_ratio,  0.30, 0.55)
spendingControl_score   = 0.5 * essential_component + 0.5 * discretionary_component
```
Apply a `−10` penalty (floored at 0) if lifestyle-creep (§5 FINANCIAL_RULES) is active.
`status`: `WARN` if either ratio over its target; `CRITICAL` if `essential_ratio > 0.70`.

### 3.3 `emergencyFund` — weight 0.20
Driver: `emergency_fund_months`.
```
emergencyFund_score = ramp(emergency_fund_months, emergency.months_min, effective_months_target)
```
`effective_months_target` = user override if set, else the variable-income target if applicable, else `emergency.months_target`.
`status`: `OK` at ≥ target, `WARN` between min and target, `CRITICAL` below min.

### 3.4 `debt` — weight 0.20
Start at 100, subtract penalties (floor 0):
| Condition | Penalty |
|---|---|
| `dti` in `(0.36, 0.43]` | −15 |
| `dti > 0.43` | −35 |
| `cc_utilisation` in `(0.30, 0.50]` | −10 |
| `cc_utilisation` in `(0.50, 0.80]` | −25 |
| `cc_utilisation > 0.80` | −40 |
| Any high-interest debt (APR ≥ 0.18) with balance | −30 |
| Revolving card balance detected | −15 |
If the user has **no debt** (confirmed or no liabilities after ≥1 linked account): `debt_score = 100`, `status OK`.
`status`: `CRITICAL` if total penalty ≥ 45, else `WARN` if ≥ 20, else `OK`.

### 3.5 `diversification` — weight 0.15
Start at 100, subtract (floor 0):
| Condition | Penalty |
|---|---|
| Equity allocation outside `±rebalance_band` of target | −10 |
| Equity allocation outside `±rebalance_band_hard` | −25 (replaces the −10) |
| Any single direct stock > 10% of equity | −10 each, max −25 |
| Any single stock > 20% of equity | −25 (replaces its −10) |
| Employer stock > 10% of net worth | −15 |
| Employer stock > 25% of net worth | −30 (replaces the −15) |
| Single AMC > 50% of MF portfolio | −10 |
| Fund overlap > 50% between top 2 MFs | −10 |
`status`: `CRITICAL` if penalty ≥ 45, `WARN` if ≥ 20, else `OK`.

### 3.6 `goalProgress` — weight 0.05
Driver: weighted completion of user-defined goals (target amount + date).
```
per_goal_progress = clamp(current_allocated / required_run_rate_to_date, 0, 1)
goalProgress_score = 100 * (Σ per_goal_progress * goal_weight) / Σ goal_weight
```
`goal_weight` defaults to equal; `required_run_rate_to_date` = straight-line accrual from goal start to target date.
If the user has **no goals defined**: sub-score is **not evaluated** (see §4), weight redistributes.

---

## 4. Weighting, evaluation, confidence

### 4.1 Base weights
`savings 0.25 · emergencyFund 0.20 · debt 0.20 · spendingControl 0.15 · diversification 0.15 · goalProgress 0.05` (sum 1.00).

### 4.2 Unevaluated sub-scores
A sub-score is `evaluated: false` when its data-sufficiency gate (§6 FINANCIAL_RULES) fails.
Its weight is redistributed **proportionally** across evaluated sub-scores:
```
w_i_effective = w_i_base / Σ(w_j_base for evaluated j)
overall_raw   = Σ (sub_score_i * w_i_effective)   over evaluated i
```
If **fewer than 3** sub-scores are evaluated, do not publish a numeric `overall` — return
`band: "INSUFFICIENT_DATA"`, `overall: null`, and a checklist of what to connect.

### 4.3 Confidence
```
confidence = 0.4 * (evaluated_weight_share)
           + 0.4 * (data_freshness_factor)
           + 0.2 * (history_depth_factor)
```
- `evaluated_weight_share` = Σ base weights of evaluated sub-scores.
- `data_freshness_factor` = `1.0` if all linked accounts refreshed ≤ 7 days ago, ramping to `0.3` at 45 days stale.
- `history_depth_factor` = `ramp(days_of_history, 30, 180) / 100`.
Rounded to 3 decimals. Surfaced in the UI as Low / Medium / High (`<0.5 / 0.5–0.8 / >0.8`).

### 4.4 Final
```
overall = min( round1(overall_raw), applicable_hard_caps... )
band    = band_for(overall)   // unless INSUFFICIENT_DATA
```

---

## 5. Recomputation

Triggered by (debounced 60s per user):
`SpendingHabitsRecalculated`, `IncomeRecorded`, `NetWorthSnapshotCreated`, `PortfolioValued`,
`AccountBalanceChanged`, `LiabilityBalanceChanged`, goal create/update, `ruleset_version` change.
Plus a **daily** scheduled recompute so `confidence` decay and trends stay current.

Skip-recompute optimisation: compute `inputsHash = sha256(canonical_json(all_driver_metrics + ruleset_version + score_spec_version))`.
If it matches the latest stored row, write nothing.

Store every distinct result in the `health_score` hypertable → powers the score trend line.

---

## 6. Worked example

Inputs (trailing 3-month):
- net income ₹1,50,000/mo; essential ₹67,500 (0.45); discretionary ₹37,500 (0.25); savings transfer ₹30,000.
- `savings_rate` = (150000 − 105000) / 150000 = **0.30**
- liquid assets ₹4,05,000; monthly essential burn ₹67,500 → `emergency_fund_months` = **6.0**; target 6.
- no debt; user confirmed.
- equity target for age 32, BALANCED: `clamp(100−32,30,90)=68` → target 68%; actual equity 60% (within ±5? no, −8 → outside band, −10). One stock at 12% of equity (−10). Penalty 20 → `diversification = 80`.
- no goals defined → `goalProgress` not evaluated.

Sub-scores:
| Sub | value | evaluated | base w |
|---|---|---|---|
| savings | `ramp(0.30, 0.10, 0.30)` = **100** | yes | 0.25 |
| spendingControl | `0.5*invramp(0.45,0.50,0.75)` = 0.5*100 + `0.5*invramp(0.25,0.30,0.55)` = 0.5*100 → **100** | yes | 0.15 |
| emergencyFund | `ramp(6.0, 3, 6)` = **100** | yes | 0.20 |
| debt | no debt → **100** | yes | 0.20 |
| diversification | **80** | yes | 0.15 |
| goalProgress | — | no | 0.05 |

Evaluated weight sum = 0.95. Effective weights scale by `1/0.95`.
```
overall_raw = (100*0.25 + 100*0.15 + 100*0.20 + 100*0.20 + 80*0.15) / 0.95
            = (25 + 15 + 20 + 20 + 12) / 0.95
            = 92 / 0.95 = 96.84
```
No hard caps apply. `overall = 96.8`, `band = EXCELLENT`.
`topDrivers`: only diversification has a gap → `["diversification"]`.
`confidence`: evaluated share 0.95; assume fresh data (1.0) and 200 days history (1.0) →
`0.4*0.95 + 0.4*1.0 + 0.2*1.0 = 0.98` → High.
