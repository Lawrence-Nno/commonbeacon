CREATE TABLE transfer_control (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO transfer_control VALUES (1);

CREATE TABLE transfer_job (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL REFERENCES app_user(id),
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('COMPANY_EXPORT','PERSONAL_EXPORT','COMPANY_IMPORT')),
    state VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    fence BIGINT NOT NULL DEFAULT 0 CHECK (fence >= 0),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    worker_id UUID,
    lease_until TIMESTAMPTZ,
    checkpoint BIGINT NOT NULL DEFAULT 0 CHECK (checkpoint >= 0),
    reserved_bytes BIGINT NOT NULL DEFAULT 805306368 CHECK (reserved_bytes BETWEEN 0 AND 805306368),
    error_code VARCHAR(48) CHECK (error_code IN ('WORK_FAILED','AUTHORIZATION_REVOKED','ATTEMPTS_EXHAUSTED',
        'JOB_EXPIRED','ARTIFACT_MISSING','ACTIVATION_RECONCILIATION_REQUIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK ((worker_id IS NULL) = (lease_until IS NULL)),
    CHECK ((kind <> 'COMPANY_IMPORT' AND state IN ('QUEUED','RUNNING','READY','FAILED','CANCELLED'))
        OR (kind = 'COMPANY_IMPORT' AND state IN ('UPLOADING','UPLOADED','VALIDATING','REVIEW_REQUIRED',
            'READY_TO_COMMIT','COMMITTING','COMPLETED','FAILED','CANCELLED')))
);
CREATE UNIQUE INDEX ux_transfer_active_requester ON transfer_job(requester_id)
    WHERE state NOT IN ('READY','COMPLETED','FAILED','CANCELLED');
CREATE INDEX ix_transfer_owner_list ON transfer_job(requester_id, created_at DESC, id DESC);
CREATE INDEX ix_transfer_claim ON transfer_job(state, created_at, id);
CREATE INDEX ix_transfer_expiry ON transfer_job(expires_at);

CREATE TABLE transfer_request (
    requester_id UUID NOT NULL REFERENCES app_user(id),
    operation VARCHAR(32) NOT NULL CHECK (operation IN ('COMPANY_EXPORT','PERSONAL_EXPORT','COMPANY_IMPORT')),
    request_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    job_id UUID NOT NULL REFERENCES transfer_job(id),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(requester_id, operation, request_key)
);
CREATE INDEX ix_transfer_request_expiry ON transfer_request(expires_at);

CREATE TABLE transfer_attempt (
    job_id UUID NOT NULL REFERENCES transfer_job(id),
    fence BIGINT NOT NULL,
    worker_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    PRIMARY KEY(job_id, fence)
);

CREATE TABLE transfer_artifact (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES transfer_job(id),
    fence BIGINT NOT NULL,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('UPLOAD','INTERMEDIATE','DOWNLOAD')),
    state VARCHAR(16) NOT NULL DEFAULT 'WRITING' CHECK (state IN ('WRITING','AVAILABLE','DELETING','DELETED','MISSING')),
    byte_limit BIGINT NOT NULL CHECK (byte_limit BETWEEN 1 AND 268435456),
    byte_count BIGINT CHECK (byte_count BETWEEN 0 AND byte_limit),
    sha256 CHAR(64) CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '24 hours',
    CHECK ((byte_count IS NULL) = (sha256 IS NULL)),
    CHECK (state <> 'AVAILABLE' OR byte_count IS NOT NULL),
    UNIQUE(job_id, id)
);
CREATE INDEX ix_transfer_artifact_job ON transfer_artifact(job_id);
CREATE INDEX ix_transfer_artifact_expiry ON transfer_artifact(state, expires_at);

-- Durable ownership skeleton. Actual parsed record payloads arrive with validation/staging.
CREATE TABLE transfer_mapping (
    job_id UUID NOT NULL REFERENCES transfer_job(id),
    entity VARCHAR(16) NOT NULL CHECK (entity IN ('users','boards','questions','replies','articles','reports','actions')),
    source_id UUID NOT NULL,
    local_id UUID NOT NULL,
    fence BIGINT NOT NULL,
    PRIMARY KEY(job_id, entity, source_id),
    UNIQUE(job_id, entity, local_id)
);
CREATE TABLE transfer_completion (
    job_id UUID PRIMARY KEY REFERENCES transfer_job(id),
    fence BIGINT NOT NULL,
    artifact_id UUID,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(job_id, artifact_id) REFERENCES transfer_artifact(job_id, id)
);
CREATE TABLE transfer_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES transfer_job(id),
    event VARCHAR(32) NOT NULL CHECK (event IN ('CREATED','CLAIMED','CONFIRMED','COMPLETED','FAILED','CANCELLED',
        'DOWNLOAD_ATTEMPT','DOWNLOAD_COMPLETED','EXPIRED','CLEANUP_STARTED','CLEANUP_COMPLETED','LEASE_EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_transfer_audit_job ON transfer_audit(job_id, id);
