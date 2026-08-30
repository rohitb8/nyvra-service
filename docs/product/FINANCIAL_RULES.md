# FINANCIAL_RULES.md — nyvra-backend

The financial logic behind *"is the user in control of their money"*. Every threshold here is a
**named, versioned parameter** with a default and a rationale. The engine reads these from a
`financial_rules` config (YAML, per-environment overridable, user-overridable where noted) — nothing
in this list is hard-coded in Java.

- **Currency:** INR. Amounts `BigDecimal`, scale 2.
- **Returns:** annualised, money-weighted (**XIRR**) for portfolios; simple period-over-period for cash-flow metrics.
- **"Monthly" figures:** trailing rolling averages, not calendar-to-date, unless stated. Default window = 3 months, with a 6- and 12-month variant surfaced for stability.
- **Rule version:** `RULESET_VERSION` string is stamped onto every `health_score` and `insight` row so historical scores stay explainable.

Terms: **essential** vs **discretionary** spend per `expense.necessity`; **corpus** = total invested + investable assets; **liquid assets** = cash + savings + liquid funds + <1yr FDs.

---

## 1. Cash-flow rules

### 1.1 Savings rate
```
savings_rate = (net_income − total_spend_excl_savings_transfer) / net_income
```
- `net_income` = trailing-window average net income (Income context).
- `total_spend_excl_savings_transfer` excludes `necessity = SAVINGS_TRANSFER` and `excluded_from_habits = true`.

| Param | Default | Rationale |
|---|---|---|
| `savings.target_rate` | `0.20` | 50/30/20 guideline; 20% is the "good" floor |
| `savings.stretch_rate` | `0.30` | Aggressive FI-oriented target |
| `savings.min_acceptable` | `0.10` | Below this → WARN insight |

### 1.2 Expense-to-income ratio (50/30/20 lens)
```
essential_ratio      = essential_spend / net_income        target ≤ 0.50
discretionary_ratio  = discretionary_spend / net_income    target ≤ 0.30
savings_investing    = 1 − essential_ratio − discretionary_ratio   target ≥ 0.20
```
| Param | Default |
|---|---|
| `ratio.essential_max` | `0.50` |
| `ratio.discretionary_max` | `0.30` |
| Breach severity | `essential_ratio > 0.70` → CRITICAL; `> 0.50` → WARN |

### 1.3 Fixed-obligations ratio
```
fixed_obligations_ratio = (rent_or_emi + insurance_premiums + essential_subscriptions + loan_emis) / net_income
```
`target ≤ 0.40`, `hard_ceiling 0.50`. Above ceiling the user has little manoeuvring room → CRITICAL.

---

## 2. Emergency fund

```
monthly_essential_burn = trailing_avg(essential_spend + fixed_obligations, window = 6 months)
emergency_fund_months  = liquid_assets / monthly_essential_burn
```
| Param | Default | Rationale |
|---|---|---|
| `emergency.months_target` | `6` | Standard for salaried single-income |
| `emergency.months_min` | `3` | Absolute floor |
| `emergency.months_target_variable_income` | `9` | Applied when income cadence is `IRREGULAR` or >1 `BUSINESS` source |
| User override | allowed | `user_preferences.emergency_fund_months_target`, clamped to `[3, 24]` |

Scoring: linear ramp from `months_min` (score 0) to `months_target` (score 100), capped at 100.
`emergency_fund_months < months_min` → CRITICAL insight regardless of overall score.

---

## 3. Debt rules

### 3.1 Debt-to-income (DTI)
```
dti = total_monthly_debt_payments / net_monthly_income      target ≤ 0.36, ceiling 0.43
```

### 3.2 Credit-card utilisation
```
cc_utilisation = sum(card_current_outstanding) / sum(card_credit_limit)   target ≤ 0.30
```
`> 0.50` → WARN, `> 0.80` → CRITICAL. Also flag any card where a **revolving balance** (interest charged) is detected.

### 3.3 High-interest debt flag
Any liability with APR ≥ `debt.high_interest_apr` (default `0.18`) and balance > 0 → CRITICAL insight
"pay this down before investing surplus", unless emergency fund `< months_min` (then emergency fund comes first).

### 3.4 Debt payoff priority (advice ordering, not automation)
1. Build `months_min` emergency buffer.
2. Clear debt with APR ≥ `debt.high_interest_apr` (avalanche order).
3. Fill emergency fund to `months_target`.
4. Tax-advantaged long-term (EPF/NPS/ELSS) to limit.
5. Invest surplus per target allocation (§4).

---

## 4. Investment / diversification rules

### 4.1 Target asset allocation by risk band and age
Equity target from a glide path; the rest split across debt / gold / cash.

```
base_equity_pct(age)        = clamp(100 − age, 30, 90)          # rule-of-thumb baseline
risk_adjustment             = { CONSERVATIVE: −15, BALANCED: 0, AGGRESSIVE: +10 }
target_equity_pct           = clamp(base_equity_pct(age) + risk_adjustment, 20, 90)
target_debt_pct             = 100 − target_equity_pct − target_gold_pct − target_cash_pct
```
| Param | Default |
|---|---|
| `alloc.gold_pct` | `5` |
| `alloc.cash_pct` | `5` (separate from emergency fund) |
| `alloc.rebalance_band` | `±5` percentage points → `AllocationDrifted` event + insight |
| `alloc.rebalance_band_hard` | `±10` pp → CRITICAL |

### 4.2 Concentration limits
| Rule | Param | Default | Severity if breached |
|---|---|---|---|
| Single direct stock | `conc.single_stock_max_pct` | `10%` of equity portfolio | WARN; `>20%` CRITICAL |
| Single sector | `conc.single_sector_max_pct` | `30%` of equity | WARN |
| Single mutual fund | `conc.single_fund_max_pct` | `35%` of MF portfolio | INFO; `>50%` WARN |
| Single AMC | `conc.single_amc_max_pct` | `50%` of MF portfolio | WARN |
| Employer stock (ESOP/RSU + direct) | `conc.employer_stock_max_pct` | `10%` of net worth | WARN; `>25%` CRITICAL (income + asset correlated) |
| Foreign equity | `conc.foreign_equity_target_pct` | `10–20%` of equity | INFO if outside band |

### 4.3 Portfolio quality checks
- **Overlap:** flag if top-2 equity MFs have > `conc.fund_overlap_max_pct` (default `50%`) holdings overlap.
- **Cash drag:** un-invested cash earmarked for investing > `alloc.cash_drag_max_pct` (default `10%` of corpus) for > 60 days → INFO.
- **Idle FD ladder:** > `50%` of debt allocation in <6-month FDs while equity is under target → INFO.

---

## 5. Net-worth & trend rules

| Rule | Param | Default | Signal |
|---|---|---|---|
| Net-worth trending down | `nw.decline_months` | `3` consecutive months | WARN |
| Liabilities growing faster than assets | `nw.liab_growth_ratio` | liabilities Δ% > assets Δ% over 6 months | WARN |
| Lifestyle creep | discretionary spend Δ% > net-income Δ% over `12` months by `> 5pp` | | INFO |
| Stale manual assets | `nw.stale_after_days` | `revaluation_cadence` elapsed | INFO ("revalue X") |

---

## 6. Data-sufficiency gates

A rule is **not evaluated** (and does not drag the score down) unless its inputs meet a minimum:

| Rule group | Minimum data |
|---|---|
| Savings rate / ratios | ≥ 2 full months of categorised transactions AND ≥ 1 income entry |
| Emergency fund | liquid assets known AND ≥ 3 months essential-spend history |
| Debt | at least one liability account linked, or user confirms "no debt" |
| Diversification | corpus ≥ `data.min_corpus` (default ₹50,000) AND ≥ 3 holdings |
| Trends | ≥ 90 days of snapshots |

Unevaluated groups reduce `confidence` (see `HEALTH_SCORE_SPEC.md`), not `overall`.

---

## 7. Rounding, edge cases, precedence

- Percentages displayed to 1 decimal; computed at full `BigDecimal` precision, `HALF_UP` only at display.
- Division-by-zero (e.g. `net_income = 0`): rule is skipped, treated as insufficient data.
- Negative net income (spend > income in window): savings rate reported as negative, clamped to `[-1, 0]` for scoring, CRITICAL insight.
- **Insight precedence** when several fire: `emergency fund < min` > `high-interest debt` > `fixed-obligations over ceiling` > everything else. Show top 3 on the dashboard, full list in Insights view.
- All parameters can be overridden per environment for testing; user-facing overrides are limited to the ones marked "User override".

---

## 8. Parameter file shape (reference)

```yaml
financial_rules:
  ruleset_version: "2026.1"
  window_months_default: 3
  savings: { target_rate: 0.20, stretch_rate: 0.30, min_acceptable: 0.10 }
  ratio:   { essential_max: 0.50, discretionary_max: 0.30 }
  emergency: { months_target: 6, months_min: 3, months_target_variable_income: 9 }
  debt: { dti_target: 0.36, dti_ceiling: 0.43, cc_util_target: 0.30, high_interest_apr: 0.18 }
  alloc: { gold_pct: 5, cash_pct: 5, rebalance_band: 5, rebalance_band_hard: 10 }
  conc: { single_stock_max_pct: 10, single_sector_max_pct: 30, single_fund_max_pct: 35,
          single_amc_max_pct: 50, employer_stock_max_pct: 10, fund_overlap_max_pct: 50 }
  data: { min_corpus: 50000 }
```

Changes to this file that alter user-visible scores require bumping `ruleset_version` and a note in `CHANGELOG`.
