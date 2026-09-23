-- Confirmed intent survives client loss; live data and completion are still one transaction.
CREATE TABLE transfer_activation (
    job_id UUID PRIMARY KEY REFERENCES transfer_job(id),
    request_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    review JSONB NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + interval '5 minutes')
);
CREATE TABLE imported_record (
    job_id UUID NOT NULL REFERENCES transfer_job(id),
    entity VARCHAR(16) NOT NULL CHECK (entity IN ('users','boards','questions','replies','articles','reports','actions')),
    source_id UUID NOT NULL,
    local_id UUID NOT NULL,
    source_instance_id UUID NOT NULL,
    origin_source_id UUID NOT NULL,
    PRIMARY KEY(job_id,entity,source_id),
    UNIQUE(entity,local_id),
    UNIQUE(entity,source_instance_id,origin_source_id)
);
CREATE TRIGGER imported_record_immutable BEFORE UPDATE OR DELETE ON imported_record
    FOR EACH ROW EXECUTE FUNCTION protect_imported_author();
CREATE TRIGGER imported_record_target_changed AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON imported_record
    FOR EACH STATEMENT EXECUTE FUNCTION advance_transfer_target_generation();
ALTER TABLE transfer_request DROP CONSTRAINT transfer_request_operation_check;
ALTER TABLE transfer_request ADD CONSTRAINT transfer_request_operation_check
    CHECK (operation IN ('COMPANY_EXPORT','PERSONAL_EXPORT','COMPANY_IMPORT','CANCEL','DRY_RUN','CONFIRM'));

-- Defence for SQL writers outside service methods. Normal services take this gate
-- at transaction entry, BEFORE SELECT FOR UPDATE/SHARE. Direct operator SQL still
-- requires maintenance coordination; triggers cannot reorder pre-existing row locks.
CREATE FUNCTION require_shared_migration_gate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT pg_try_advisory_xact_lock_shared(736284910251) THEN
        RAISE EXCEPTION 'IMPORT_IN_PROGRESS' USING ERRCODE='55P03';
    END IF;
    RETURN NULL;
END $$;
DO $$ DECLARE t TEXT; BEGIN
    FOREACH t IN ARRAY ARRAY['app_user','imported_author','imported_record','board','question','reply',
        'knowledge_article','content_report','moderation_action'] LOOP
        EXECUTE format('CREATE TRIGGER migration_write_gate BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION require_shared_migration_gate()',t);
    END LOOP;
END $$;
