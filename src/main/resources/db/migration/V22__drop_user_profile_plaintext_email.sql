-- Contract step of the two-step email encryption retrofit (database/decisions.md §1; expand was V5).
-- The app has read and written only email_encrypted / email_hash since V5, and UserEmailBackfill has run
-- in every environment, so the legacy plaintext column can go. Irreversible: plaintext emails are gone.
ALTER TABLE user_profile
    DROP COLUMN email;
