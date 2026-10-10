-- V7 — free-text note on an expense (carried by split parts: API `SplitExpenseRequest.parts[].note`).
-- Additive and nullable, so it is safe on the partitioned table and for existing rows.
ALTER TABLE expense ADD COLUMN note TEXT;
