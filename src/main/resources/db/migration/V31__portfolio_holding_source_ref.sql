-- V31 — Ties an ingested (AA) position to the folio or demat account that reported it, so the same fund held in
-- two folios stays two positions and a position can be closed when its holding vanishes from that account.
-- Format: "<fipId>|<accountRef>". NULL for manual positions and for AA positions created before this migration.
ALTER TABLE portfolio_holding ADD COLUMN source_ref TEXT;

CREATE INDEX idx_portfolio_holding_source_ref ON portfolio_holding (user_id, source_ref) WHERE source_ref IS NOT NULL;
