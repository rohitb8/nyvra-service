# Aggregator adapter and ingestion jobs

No Account Aggregator provider has been chosen. `AggregatorGateway` is the only seam; two implementations exist.

| `nyvra.ingestion.aa.provider` | Class | Use |
|---|---|---|
| `stub` (default) | `StubAggregatorGateway` | local, tests: approves everything, returns no data |
| `http` | `HttpAggregatorGateway` | any REST AA gateway matching the assumed shape below |

## `http` settings (env vars, never committed)

`nyvra.ingestion.aa.http.base-url`, `.client-id`, `.client-secret` (required); `.timeout` (10s), `.max-polls` (15),
`.poll-interval` (2s).

## Assumed provider shape

Auth headers `x-client-id` / `x-client-secret`. `POST /consents`, `GET /consents/{handle}`,
`POST /consents/{consentId}/revoke`, `POST /data-sessions`, `GET /data-sessions/{id}` (poll until `READY`).
Payload `type` is a `PayloadType` name and `data` follows the shapes in the `RecordNormaliser` Javadoc. A real
provider that differs is a change to `HttpAggregatorGateway` only (paths, field names, auth).

## Jobs (run only with `nyvra.jobs.enabled=true`, ShedLock'd)

- Raw-record purge: `nyvra.ingestion.purge.cron` (default `0 0 3 * * *` UTC). Deletes `raw_financial_record` rows
  with `purge_after < today` whose fetch has a COMPLETED normalisation run.
- Consent expiry: `nyvra.ingestion.consent-expiry.cron` (default hourly). Moves ACTIVE/PAUSED consents past
  `expires_at` to EXPIRED and publishes `ConsentExpired`.

## Event consumers

`TransactionsIngested` is consumed in `accounts` (`IngestedTransactionWriter`: ledger + account) and, for debits,
`expense` (`IngestedExpenseRecorder`: rule categorisation). `AccountSnapshotIngested` and `HoldingsIngested` are
published but have no consumer yet (net worth / portfolio).
