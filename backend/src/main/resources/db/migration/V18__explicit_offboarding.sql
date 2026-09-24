CREATE TABLE erasure_job (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL,
    instance_id UUID NOT NULL,
    scope VARCHAR(12) NOT NULL CHECK (scope IN ('ACCOUNT','COMPANY')),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PREVIEW','RESTORE_QUEUED','RUNNING','COMPLETED')),
    preview JSONB NOT NULL,
    digest CHAR(64) NOT NULL,
    receipt_hash CHAR(64),
    phase INTEGER NOT NULL DEFAULT 0,
    processed BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX erasure_one_active ON erasure_job(state) WHERE state='RUNNING';
CREATE UNIQUE INDEX erasure_one_preview ON erasure_job(requester_id) WHERE state='PREVIEW';
-- Minimal restore suppression ledger. No names, emails, content, tokens or credentials.
CREATE TABLE erasure_tombstone (
    job_id UUID PRIMARY KEY,
    instance_id UUID NOT NULL,
    subject_id UUID NOT NULL,
    scope VARCHAR(12) NOT NULL CHECK (scope IN ('ACCOUNT','COMPANY')),
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    backup_purge_after TIMESTAMPTZ NOT NULL
);

ALTER TABLE app_user DROP CONSTRAINT ck_app_user_account_state;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_account_state CHECK (
    (account_state='ACTIVE' AND email IS NOT NULL AND password_hash IS NOT NULL)
    OR (account_state='IMPORTED_INACTIVE' AND email IS NULL AND password_hash IS NULL AND role='MEMBER')
    OR (account_state='ERASED' AND email IS NULL AND password_hash IS NULL AND role='MEMBER' AND display_name='Deleted member')
);
CREATE FUNCTION protect_erased_account() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.account_state='ERASED' AND NEW.account_state<>'ERASED' THEN
        RAISE EXCEPTION 'Erased accounts cannot be reactivated' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER app_user_erased_state BEFORE UPDATE ON app_user FOR EACH ROW EXECUTE FUNCTION protect_erased_account();

-- Serialize identity statements before acquiring user-row locks. Concurrent demotions
-- and deletion cannot both observe the other administrator as a surviving account.
CREATE FUNCTION serialize_identity_changes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(736284910252);
    RETURN NULL;
END $$;
-- PostgreSQL orders same-kind triggers by name: migration_write_gate must run first.
CREATE TRIGGER zz_identity_change_gate BEFORE INSERT OR UPDATE OR DELETE ON app_user
    FOR EACH STATEMENT EXECUTE FUNCTION serialize_identity_changes();
CREATE FUNCTION protect_last_administrator() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.role='ADMINISTRATOR' AND OLD.account_state='ACTIVE'
        AND (TG_OP='DELETE' OR NEW.role<>'ADMINISTRATOR' OR NEW.account_state<>'ACTIVE')
        AND (SELECT count(*) FROM app_user WHERE role='ADMINISTRATOR' AND account_state='ACTIVE')<=1 THEN
        RAISE EXCEPTION 'LAST_ADMINISTRATOR' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER app_user_last_admin BEFORE UPDATE OR DELETE ON app_user
    FOR EACH ROW EXECUTE FUNCTION protect_last_administrator();

CREATE OR REPLACE FUNCTION require_shared_migration_gate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT pg_try_advisory_xact_lock_shared(736284910251) THEN
        RAISE EXCEPTION 'IMPORT_IN_PROGRESS' USING ERRCODE='55P03';
    END IF;
    IF EXISTS(SELECT 1 FROM erasure_job WHERE state IN ('RUNNING','RESTORE_QUEUED'))
        AND current_setting('commonbeacon.erasure_worker',true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'ERASURE_IN_PROGRESS' USING ERRCODE='55P03';
    END IF;
    RETURN NULL;
END $$;

-- Operator-only, offline restore replay. Import ONLY the separately retained ledger
-- for this source instance before starting the application or opening network access.
-- A tombstone already in the restored snapshot needs no second erasure: its job is
-- either durably running, or completed before that snapshot was taken.
CREATE FUNCTION queue_restored_erasure(p_job UUID,p_instance UUID,p_subject UUID,
    p_scope VARCHAR,p_confirmed TIMESTAMPTZ,p_purge TIMESTAMPTZ) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(736284910251);
    IF p_instance IS DISTINCT FROM (SELECT source_instance_id FROM transfer_instance WHERE id=1)
        OR p_scope NOT IN ('ACCOUNT','COMPANY') OR p_confirmed IS NULL OR p_purge<p_confirmed THEN
        RAISE EXCEPTION 'INVALID_ERASURE_LEDGER' USING ERRCODE='23514';
    END IF;
    IF EXISTS(SELECT 1 FROM erasure_tombstone WHERE job_id=p_job) THEN
        IF NOT EXISTS(SELECT 1 FROM erasure_tombstone WHERE job_id=p_job AND instance_id=p_instance
            AND subject_id=p_subject AND scope=p_scope AND confirmed_at=p_confirmed AND backup_purge_after=p_purge) THEN
            RAISE EXCEPTION 'ERASURE_LEDGER_CONFLICT' USING ERRCODE='23514';
        END IF;
        RETURN FALSE;
    END IF;
    IF p_scope='COMPANY' AND NOT EXISTS(SELECT 1 FROM app_user WHERE id=p_subject AND role='ADMINISTRATOR' AND account_state='ACTIVE') THEN
        RAISE EXCEPTION 'RESTORE_ADMINISTRATOR_REQUIRED' USING ERRCODE='23514';
    END IF;
    INSERT INTO erasure_tombstone VALUES(p_job,p_instance,p_subject,p_scope,p_confirmed,p_purge);
    INSERT INTO erasure_job(id,requester_id,instance_id,scope,state,preview,digest,confirmed_at,expires_at)
        VALUES(p_job,p_subject,p_instance,p_scope,'RESTORE_QUEUED','{}',repeat('0',64),p_confirmed,clock_timestamp()+interval '30 days')
        ON CONFLICT(id) DO UPDATE SET requester_id=p_subject,instance_id=p_instance,scope=p_scope,
            state='RESTORE_QUEUED',preview='{}',digest=repeat('0',64),receipt_hash=NULL,phase=0,processed=0,
            error_code=NULL,next_attempt_at=clock_timestamp(),confirmed_at=p_confirmed,completed_at=NULL,
            expires_at=clock_timestamp()+interval '30 days';
    RETURN TRUE;
END $$;
CREATE OR REPLACE FUNCTION protect_imported_author() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('commonbeacon.erasure_worker',true)='on'
        AND EXISTS(SELECT 1 FROM erasure_job WHERE state='RUNNING' AND scope='COMPANY') THEN
        IF TG_OP='DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Imported author provenance is immutable' USING ERRCODE='23514';
END $$;
