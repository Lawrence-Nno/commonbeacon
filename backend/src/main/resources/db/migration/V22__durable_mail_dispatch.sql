ALTER TABLE email_outbox DROP CONSTRAINT email_outbox_failure_code_check;
ALTER TABLE email_outbox ADD CONSTRAINT email_outbox_failure_code_check CHECK
    (failure_code IN ('TRANSIENT','TIMEOUT','PERMANENT','CONFIGURATION','SUPPRESSED','KEY_UNAVAILABLE','PAYLOAD_INVALID',
      'STALE_CHALLENGE','SUBJECT_INELIGIBLE','LEASE_EXPIRED','RESTORE_CANCELLED','ATTEMPTS_EXHAUSTED','OPERATOR_CANCELLED'));
CREATE INDEX email_outbox_leases ON email_outbox(lease_until,id) WHERE state='SENDING';

-- Aggregate transport counters, not identity admission. No subjects, recipients or secrets.
CREATE TABLE email_transport_budget (
    window_start TIMESTAMPTZ PRIMARY KEY,
    ordinary INTEGER NOT NULL DEFAULT 0 CHECK (ordinary BETWEEN 0 AND 600),
    alerts INTEGER NOT NULL DEFAULT 0 CHECK (alerts BETWEEN 0 AND 600),
    retries INTEGER NOT NULL DEFAULT 0 CHECK (retries BETWEEN 0 AND 600),
    CHECK (ordinary+alerts<=600 AND retries<=ordinary+alerts)
);
CREATE TRIGGER migration_write_gate BEFORE INSERT OR UPDATE OR DELETE ON email_transport_budget
    FOR EACH STATEMENT EXECUTE FUNCTION require_shared_migration_gate();

-- Shared by worker eligibility and deployment-only operator commands. The worker
-- passes its injected UTC clock; operator entry points always use database time.
CREATE FUNCTION email_outbox_eligible(p_id UUID,p_now TIMESTAMPTZ) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS(SELECT 1 FROM email_outbox o JOIN app_user u ON u.id=o.subject_id
      WHERE o.id=p_id AND u.account_state IN ('ACTIVE','PENDING_VERIFICATION')
        AND o.created_at<=p_now AND o.expires_at>p_now AND (
          o.generation IS NULL OR EXISTS(SELECT 1 FROM email_challenge c
            WHERE c.id=o.challenge_id AND c.subject_id=u.id AND c.purpose=o.message_type
              AND c.generation=o.generation AND c.consumed_at IS NULL AND c.revoked_at IS NULL AND c.expires_at>p_now
              AND CASE o.message_type
                WHEN 'VERIFICATION' THEN c.generation=u.verification_generation AND c.intended_email=u.email AND u.email_verified_at IS NULL
                WHEN 'PASSWORD_RESET' THEN c.generation=u.password_reset_generation AND c.intended_email=u.email
                WHEN 'EMAIL_CHANGE' THEN c.generation=u.email_change_generation AND u.account_state='ACTIVE' AND u.email_verified_at IS NOT NULL
                  AND EXISTS(SELECT 1 FROM pending_email_change p WHERE p.subject_id=u.id AND p.intended_email=c.intended_email AND p.generation=c.generation AND p.expires_at>p_now)
                ELSE false END)))
$$;

-- DB access is the operator boundary: no public/controller route. Version guards
-- reject stale actions, and retry cannot resurrect purged terminal messages.
CREATE FUNCTION operate_email_outbox(p_id UUID,p_version BIGINT,p_action VARCHAR) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE o email_outbox; n TIMESTAMPTZ;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(736284910251);
    IF EXISTS(SELECT 1 FROM erasure_job WHERE state IN ('RUNNING','RESTORE_QUEUED')) THEN RETURN false; END IF;
    PERFORM pg_advisory_xact_lock(736284910252);
    SELECT * INTO o FROM email_outbox WHERE id=p_id FOR UPDATE;
    n:=clock_timestamp();
    IF NOT FOUND OR o.lease_version<>p_version OR o.created_at>n THEN RETURN false; END IF;
    IF p_action='cancel' AND o.state IN ('QUEUED','RETRY_WAIT','SENDING') THEN
      UPDATE email_outbox SET state='CANCELLED',failure_code='OPERATOR_CANCELLED',nonce=NULL,ciphertext=NULL,
        lease_owner=NULL,lease_until=NULL,lease_version=lease_version+1,updated_at=n WHERE id=p_id;
      RETURN true;
    END IF;
    IF p_action='retry' AND o.state='RETRY_WAIT' AND o.attempts<6 AND email_outbox_eligible(p_id,n) THEN
      UPDATE email_outbox SET next_attempt_at=n,lease_version=lease_version+1,updated_at=n WHERE id=p_id;
      RETURN true;
    END IF;
    RETURN false;
END $$;
