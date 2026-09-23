ALTER TABLE transfer_job ADD COLUMN import_provider varchar(20) NOT NULL DEFAULT 'NATIVE';
ALTER TABLE transfer_job ADD CONSTRAINT transfer_job_import_provider_check
    CHECK (import_provider IN ('NATIVE', 'DISCOURSE') AND (kind = 'COMPANY_IMPORT' OR import_provider = 'NATIVE'));
