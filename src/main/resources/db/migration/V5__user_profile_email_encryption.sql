-- Expand step of the two-step email encryption retrofit (database/decisions.md §1).
-- Adds the encrypted column + blind index next to the legacy plaintext `email`. The app now reads and
-- writes only the new columns; UserEmailBackfill encrypts existing rows at startup (needs the app's keys).
-- The contract step (DROP COLUMN email) is a later migration, once the backfill has run in every env.
ALTER TABLE user_profile
    ADD COLUMN email_encrypted BYTEA,
    ADD COLUMN email_hash      TEXT UNIQUE;
