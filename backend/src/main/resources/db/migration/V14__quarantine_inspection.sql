CREATE TABLE transfer_inspection (
    job_id UUID PRIMARY KEY REFERENCES transfer_job(id),
    artifact_id UUID NOT NULL REFERENCES transfer_artifact(id),
    archive_sha256 CHAR(64) NOT NULL CHECK (archive_sha256 ~ '^[a-f0-9]{64}$'),
    valid BOOLEAN NOT NULL,
    rows_checked BIGINT NOT NULL CHECK (rows_checked BETWEEN 0 AND 40001),
    total_errors BIGINT NOT NULL CHECK (total_errors >= 0),
    issues JSONB NOT NULL CHECK (jsonb_typeof(issues)='array' AND jsonb_array_length(issues)<=1000),
    inspected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE transfer_audit DROP CONSTRAINT transfer_audit_event_check;
ALTER TABLE transfer_audit ADD CONSTRAINT transfer_audit_event_check CHECK (event IN (
    'CREATED','CLAIMED','CONFIRMED','COMPLETED','FAILED','CANCELLED','DOWNLOAD_ATTEMPT',
    'DOWNLOAD_COMPLETED','EXPIRED','CLEANUP_STARTED','CLEANUP_COMPLETED','LEASE_EXPIRED',
    'UPLOADED','INSPECTED'));
