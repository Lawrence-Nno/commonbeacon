-- Private transfer data, never queried by community repositories.
CREATE TABLE transfer_dry_run (
    job_id UUID PRIMARY KEY REFERENCES transfer_job(id),
    artifact_id UUID NOT NULL REFERENCES transfer_artifact(id),
    archive_sha256 CHAR(64) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    validation_version INTEGER NOT NULL DEFAULT 1,
    mapping_version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','REVIEWED','STALE')),
    manifest JSONB,
    report JSONB,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + interval '24 hours')
);
CREATE TABLE transfer_stage (
    job_id UUID NOT NULL REFERENCES transfer_dry_run(job_id) ON DELETE CASCADE,
    entity VARCHAR(16) NOT NULL CHECK (entity IN ('users','boards','questions','replies','acceptances','articles','contacts','reports','actions')),
    source_id UUID NOT NULL,
    line BIGINT NOT NULL CHECK (line BETWEEN 1 AND 40001),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='object'),
    payload_sha256 CHAR(64) NOT NULL,
    byte_count INTEGER NOT NULL CHECK (byte_count BETWEEN 1 AND 262144),
    PRIMARY KEY(job_id,entity,source_id)
);
ALTER TABLE transfer_request DROP CONSTRAINT transfer_request_operation_check;
ALTER TABLE transfer_request ADD CONSTRAINT transfer_request_operation_check
    CHECK (operation IN ('COMPANY_EXPORT','PERSONAL_EXPORT','COMPANY_IMPORT','CANCEL','DRY_RUN'));

-- A sequence avoids introducing a domain-row -> shared-counter-row lock cycle.
-- Rollbacks and no-op writes conservatively invalidate reviews too.
CREATE SEQUENCE transfer_target_generation;
SELECT nextval('transfer_target_generation');
CREATE FUNCTION advance_transfer_target_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM nextval('transfer_target_generation');
    RETURN NULL;
END $$;
DO $$ DECLARE t TEXT; BEGIN
    FOREACH t IN ARRAY ARRAY['app_user','imported_author','board','question','reply',
        'knowledge_article','content_report','moderation_action'] LOOP
        EXECUTE format('CREATE TRIGGER transfer_target_changed AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION advance_transfer_target_generation()',t);
    END LOOP;
END $$;
