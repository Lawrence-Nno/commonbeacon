CREATE TABLE transfer_instance (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    source_instance_id UUID NOT NULL DEFAULT gen_random_uuid()
);
INSERT INTO transfer_instance(id) VALUES (1);
ALTER TABLE transfer_job ADD COLUMN include_contacts BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE transfer_job ADD COLUMN include_moderation_history BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE transfer_job DROP CONSTRAINT transfer_job_error_code_check;
ALTER TABLE transfer_job ADD CONSTRAINT transfer_job_error_code_check CHECK (error_code IN (
    'WORK_FAILED','AUTHORIZATION_REVOKED','ATTEMPTS_EXHAUSTED','JOB_EXPIRED','ARTIFACT_MISSING',
    'ACTIVATION_RECONCILIATION_REQUIRED','INVALID_SOURCE_DATA','TRANSFER_LIMIT_EXCEEDED','SNAPSHOT_TIMEOUT'
));
