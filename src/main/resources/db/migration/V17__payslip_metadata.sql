-- V17 — Payslip upload metadata: what the API reports about the stored file and where parsing stands.
-- Additive. payslip_document is empty before this migration (no upload endpoint existed), so the
-- NOT NULL columns take a placeholder default that is dropped straight away.
ALTER TABLE payslip_document
    ADD COLUMN file_name    TEXT   NOT NULL DEFAULT 'payslip',
    ADD COLUMN content_type TEXT   NOT NULL DEFAULT 'application/pdf'
        CHECK (content_type IN ('application/pdf', 'image/png', 'image/jpeg')),
    ADD COLUMN size_bytes   BIGINT NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
    ADD COLUMN parse_status TEXT   NOT NULL DEFAULT 'PENDING'
        CHECK (parse_status IN ('PENDING', 'PARSED', 'FAILED'));

ALTER TABLE payslip_document
    ALTER COLUMN file_name    DROP DEFAULT,
    ALTER COLUMN content_type DROP DEFAULT,
    ALTER COLUMN size_bytes   DROP DEFAULT;
