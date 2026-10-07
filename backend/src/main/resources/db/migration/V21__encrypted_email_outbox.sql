CREATE TABLE email_outbox (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    message_type VARCHAR(32) NOT NULL CHECK (message_type IN ('VERIFICATION','PASSWORD_RESET','EMAIL_CHANGE','PASSWORD_CHANGED','EMAIL_CHANGED_OLD','EMAIL_CHANGED_NEW')),
    event_id UUID NOT NULL,
    template_version INTEGER NOT NULL CHECK (template_version=1),
    challenge_id UUID REFERENCES email_challenge(id) ON DELETE SET NULL,
    generation BIGINT,
    key_id VARCHAR(40) NOT NULL CHECK (key_id ~ '^[a-zA-Z0-9_-]{1,40}$'),
    nonce BYTEA,
    ciphertext BYTEA,
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','SENDING','ACCEPTED','RETRY_WAIT','FAILED','CANCELLED','EXPIRED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 6),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner UUID,
    lease_version BIGINT NOT NULL DEFAULT 0 CHECK (lease_version>=0),
    lease_until TIMESTAMPTZ,
    provider_correlation UUID,
    failure_code VARCHAR(24) CHECK (failure_code IN ('TRANSIENT','PERMANENT','KEY_UNAVAILABLE','PAYLOAD_INVALID','STALE_CHALLENGE','SUBJECT_INELIGIBLE','LEASE_EXPIRED','RESTORE_CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE(subject_id,message_type,event_id),
    CHECK (expires_at>created_at AND updated_at>=created_at),
    CHECK ((message_type IN ('VERIFICATION','PASSWORD_RESET','EMAIL_CHANGE') AND generation IS NOT NULL AND generation>0)
        OR (message_type IN ('PASSWORD_CHANGED','EMAIL_CHANGED_OLD','EMAIL_CHANGED_NEW') AND challenge_id IS NULL AND generation IS NULL)),
    CHECK ((state IN ('QUEUED','SENDING','RETRY_WAIT') AND nonce IS NOT NULL AND ciphertext IS NOT NULL AND octet_length(nonce)=12 AND octet_length(ciphertext) BETWEEN 16 AND 4096)
        OR (state IN ('ACCEPTED','FAILED','CANCELLED','EXPIRED') AND nonce IS NULL AND ciphertext IS NULL)),
    CHECK ((state='SENDING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (state<>'SENDING' AND lease_owner IS NULL AND lease_until IS NULL))
);
CREATE UNIQUE INDEX email_outbox_nonce ON email_outbox(key_id,nonce) WHERE nonce IS NOT NULL;
CREATE INDEX email_outbox_due ON email_outbox(next_attempt_at,id) WHERE state IN ('QUEUED','RETRY_WAIT');
CREATE INDEX email_outbox_subject ON email_outbox(subject_id);
CREATE INDEX email_outbox_challenge ON email_outbox(challenge_id);
CREATE TRIGGER migration_write_gate BEFORE INSERT OR UPDATE OR DELETE ON email_outbox
    FOR EACH STATEMENT EXECUTE FUNCTION require_shared_migration_gate();

CREATE FUNCTION protect_email_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF ROW(NEW.id,NEW.subject_id,NEW.message_type,NEW.event_id,NEW.template_version,NEW.generation,NEW.key_id,NEW.created_at,NEW.expires_at)
            IS DISTINCT FROM ROW(OLD.id,OLD.subject_id,OLD.message_type,OLD.event_id,OLD.template_version,OLD.generation,OLD.key_id,OLD.created_at,OLD.expires_at)
            OR (NEW.challenge_id IS DISTINCT FROM OLD.challenge_id AND NEW.challenge_id IS NOT NULL)
            OR (NEW.ciphertext IS NOT NULL AND (NEW.ciphertext IS DISTINCT FROM OLD.ciphertext OR NEW.nonce IS DISTINCT FROM OLD.nonce))
            OR NEW.attempts<OLD.attempts OR NEW.lease_version<OLD.lease_version
            OR (OLD.state IN ('ACCEPTED','FAILED','CANCELLED','EXPIRED') AND NEW.state<>OLD.state) THEN
            RAISE EXCEPTION 'EMAIL_OUTBOX_IMMUTABLE' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.state IN ('QUEUED','SENDING','RETRY_WAIT') AND (
        NOT EXISTS(SELECT 1 FROM app_user WHERE id=NEW.subject_id AND account_state IN ('ACTIVE','PENDING_VERIFICATION'))
        OR (NEW.generation IS NOT NULL AND NOT EXISTS(SELECT 1 FROM email_challenge WHERE id=NEW.challenge_id AND subject_id=NEW.subject_id
            AND purpose=NEW.message_type AND generation=NEW.generation AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at=NEW.expires_at))) THEN
        RAISE EXCEPTION 'INELIGIBLE_MAIL_INTENT' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER email_outbox_binding BEFORE INSERT OR UPDATE ON email_outbox FOR EACH ROW EXECUTE FUNCTION protect_email_outbox();

CREATE FUNCTION cancel_challenge_mail() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.consumed_at IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
        UPDATE email_outbox SET state='CANCELLED',ciphertext=NULL,nonce=NULL,lease_owner=NULL,lease_until=NULL,
            lease_version=lease_version+1,failure_code='STALE_CHALLENGE',updated_at=greatest(created_at,clock_timestamp())
            WHERE challenge_id=OLD.id AND state IN ('QUEUED','SENDING','RETRY_WAIT');
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END $$;
CREATE TRIGGER email_challenge_mail_update AFTER UPDATE OF consumed_at,revoked_at ON email_challenge FOR EACH ROW EXECUTE FUNCTION cancel_challenge_mail();
CREATE TRIGGER email_challenge_mail_delete BEFORE DELETE ON email_challenge FOR EACH ROW EXECUTE FUNCTION cancel_challenge_mail();

CREATE FUNCTION cancel_ineligible_subject_mail() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.account_state NOT IN ('ACTIVE','PENDING_VERIFICATION') THEN
        UPDATE email_outbox SET state='CANCELLED',ciphertext=NULL,nonce=NULL,lease_owner=NULL,lease_until=NULL,
            lease_version=lease_version+1,failure_code='SUBJECT_INELIGIBLE',updated_at=greatest(created_at,clock_timestamp())
            WHERE subject_id=NEW.id AND state IN ('QUEUED','SENDING','RETRY_WAIT');
    END IF;RETURN NULL;
END $$;
CREATE TRIGGER app_user_mail_storage AFTER UPDATE OF account_state ON app_user FOR EACH ROW EXECUTE FUNCTION cancel_ineligible_subject_mail();

-- Offline restore replay closes admission and purges restored mail before returning.
ALTER FUNCTION queue_restored_erasure(UUID,UUID,UUID,VARCHAR,TIMESTAMPTZ,TIMESTAMPTZ) RENAME TO queue_restored_erasure_without_mail;
CREATE FUNCTION queue_restored_erasure(p_job UUID,p_instance UUID,p_subject UUID,p_scope VARCHAR,p_confirmed TIMESTAMPTZ,p_purge TIMESTAMPTZ)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE result BOOLEAN; previous_setting TEXT;
BEGIN
    PERFORM pg_advisory_xact_lock(736284910251);
    PERFORM pg_advisory_xact_lock(736284910252);
    result:=queue_restored_erasure_without_mail(p_job,p_instance,p_subject,p_scope,p_confirmed,p_purge);
    previous_setting:=current_setting('commonbeacon.erasure_worker',true);
    PERFORM set_config('commonbeacon.erasure_worker','on',true);
    UPDATE email_outbox SET state='CANCELLED',ciphertext=NULL,nonce=NULL,lease_owner=NULL,lease_until=NULL,
        lease_version=lease_version+1,failure_code='RESTORE_CANCELLED',updated_at=greatest(created_at,clock_timestamp())
        WHERE (p_scope='COMPANY' OR subject_id=p_subject) AND state IN ('QUEUED','SENDING','RETRY_WAIT');
    PERFORM set_config('commonbeacon.erasure_worker',coalesce(previous_setting,''),true);
    RETURN result;
END $$;
