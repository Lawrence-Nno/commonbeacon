-- Storage only: registration and authorization remain in TRANSITION mode.
ALTER TABLE app_user
    ADD COLUMN email_verified_at TIMESTAMPTZ,
    ADD COLUMN auth_epoch BIGINT NOT NULL DEFAULT 0 CHECK (auth_epoch >= 0),
    ADD COLUMN verification_generation BIGINT NOT NULL DEFAULT 0 CHECK (verification_generation >= 0),
    ADD COLUMN password_reset_generation BIGINT NOT NULL DEFAULT 0 CHECK (password_reset_generation >= 0),
    ADD COLUMN email_change_generation BIGINT NOT NULL DEFAULT 0 CHECK (email_change_generation >= 0);

-- NULL means unknown, including every pre-upgrade ACTIVE account. No fabricated proof.
ALTER TABLE app_user DROP CONSTRAINT ck_app_user_account_state;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_account_state CHECK (
    (account_state IN ('ACTIVE','SUSPENDED') AND email IS NOT NULL AND password_hash IS NOT NULL)
    OR (account_state = 'PENDING_VERIFICATION' AND email IS NOT NULL AND password_hash IS NOT NULL
        AND role = 'MEMBER' AND email_verified_at IS NULL)
    OR (account_state = 'IMPORTED_INACTIVE' AND email IS NULL AND password_hash IS NULL
        AND role = 'MEMBER' AND email_verified_at IS NULL)
    OR (account_state = 'ERASED' AND email IS NULL AND password_hash IS NULL
        AND role = 'MEMBER' AND display_name = 'Deleted member' AND email_verified_at IS NULL)
);

CREATE FUNCTION prepare_identity_proof() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.verification_generation < OLD.verification_generation
        OR NEW.password_reset_generation < OLD.password_reset_generation
        OR NEW.email_change_generation < OLD.email_change_generation THEN
        RAISE EXCEPTION 'CHALLENGE_GENERATION_CANNOT_DECREASE' USING ERRCODE='23514';
    END IF;
    IF NEW.account_state = 'ERASED' THEN NEW.email_verified_at := NULL;
    ELSIF NEW.email IS DISTINCT FROM OLD.email AND NEW.email_verified_at IS NOT DISTINCT FROM OLD.email_verified_at THEN
        NEW.email_verified_at := NULL;
    END IF;
    RETURN NEW;
END $$;
-- Runs before the existing revision trigger, which must see the final proof value.
CREATE TRIGGER account_identity_proof BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION prepare_identity_proof();

CREATE OR REPLACE FUNCTION advance_auth_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.role IS DISTINCT FROM OLD.role OR NEW.password_hash IS DISTINCT FROM OLD.password_hash
        OR NEW.email IS DISTINCT FROM OLD.email OR NEW.account_state IS DISTINCT FROM OLD.account_state
        OR NEW.email_verified_at IS DISTINCT FROM OLD.email_verified_at THEN
        NEW.auth_revision := OLD.auth_revision + 1;
        NEW.auth_epoch := OLD.auth_epoch + 1;
    ELSE
        NEW.auth_revision := OLD.auth_revision;
        -- Explicit invalidation may advance the epoch independently; it can never go backwards.
        NEW.auth_epoch := greatest(OLD.auth_epoch, NEW.auth_epoch);
    END IF;
    RETURN NEW;
END $$;

CREATE TABLE pending_email_change (
    subject_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    intended_email VARCHAR(254) NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (intended_email = lower(btrim(intended_email)) AND char_length(intended_email) BETWEEN 3 AND 254),
    CHECK (expires_at > created_at)
);
CREATE INDEX pending_email_change_expiry ON pending_email_change(expires_at);

CREATE TABLE email_challenge (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    purpose VARCHAR(24) NOT NULL CHECK (purpose IN ('VERIFICATION','PASSWORD_RESET','EMAIL_CHANGE')),
    intended_email VARCHAR(254) NOT NULL,
    token_digest VARCHAR(64) NOT NULL UNIQUE CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    generation BIGINT NOT NULL CHECK (generation > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
    CHECK (intended_email = lower(btrim(intended_email)) AND char_length(intended_email) BETWEEN 3 AND 254),
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);
-- Expired rows must be explicitly revoked before replacement; time-dependent index predicates are unsafe.
CREATE UNIQUE INDEX email_challenge_live_purpose ON email_challenge(subject_id,purpose)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
CREATE INDEX email_challenge_expiry ON email_challenge(expires_at);

CREATE FUNCTION validate_email_identity_storage() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE account app_user%ROWTYPE; expected_generation BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'email_challenge' AND TG_OP = 'UPDATE' THEN
        IF ROW(NEW.id,NEW.subject_id,NEW.purpose,NEW.intended_email,NEW.token_digest,NEW.generation,NEW.created_at,NEW.expires_at)
            IS DISTINCT FROM ROW(OLD.id,OLD.subject_id,OLD.purpose,OLD.intended_email,OLD.token_digest,OLD.generation,OLD.created_at,OLD.expires_at)
            OR (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at)
            OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at)
            OR NEW.attempts < OLD.attempts THEN
            RAISE EXCEPTION 'CHALLENGE_BINDING_IMMUTABLE' USING ERRCODE='23514';
        END IF;
    END IF;
    SELECT * INTO account FROM app_user WHERE id=NEW.subject_id FOR UPDATE;
    IF NOT FOUND OR account.account_state NOT IN ('ACTIVE','PENDING_VERIFICATION') THEN
        RAISE EXCEPTION 'INELIGIBLE_EMAIL_SUBJECT' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME = 'pending_email_change' THEN
        IF account.account_state <> 'ACTIVE' OR account.email_verified_at IS NULL
            OR NEW.intended_email = account.email OR NEW.generation <> account.email_change_generation THEN
            RAISE EXCEPTION 'INVALID_PENDING_EMAIL' USING ERRCODE='23514';
        END IF;
    ELSIF TG_OP = 'INSERT' OR (NEW.consumed_at IS NULL AND NEW.revoked_at IS NULL) THEN
        expected_generation := CASE NEW.purpose
            WHEN 'VERIFICATION' THEN account.verification_generation
            WHEN 'PASSWORD_RESET' THEN account.password_reset_generation
            WHEN 'EMAIL_CHANGE' THEN account.email_change_generation END;
        IF NEW.generation <> expected_generation THEN
            RAISE EXCEPTION 'INVALID_CHALLENGE_GENERATION' USING ERRCODE='23514';
        END IF;
        IF NEW.purpose = 'EMAIL_CHANGE' THEN
            IF account.account_state <> 'ACTIVE' OR account.email_verified_at IS NULL
                OR NOT EXISTS(SELECT 1 FROM pending_email_change p WHERE p.subject_id=NEW.subject_id
                AND p.intended_email=NEW.intended_email AND p.generation=NEW.generation
                AND p.expires_at >= NEW.expires_at) THEN
                RAISE EXCEPTION 'INVALID_PENDING_EMAIL' USING ERRCODE='23514';
            END IF;
        ELSIF NEW.intended_email <> account.email THEN
            RAISE EXCEPTION 'INVALID_CHALLENGE_ADDRESS' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER email_identity_subject BEFORE INSERT OR UPDATE ON pending_email_change
    FOR EACH ROW EXECUTE FUNCTION validate_email_identity_storage();
CREATE TRIGGER email_identity_subject BEFORE INSERT OR UPDATE ON email_challenge
    FOR EACH ROW EXECUTE FUNCTION validate_email_identity_storage();

CREATE TRIGGER migration_write_gate BEFORE INSERT OR UPDATE OR DELETE ON pending_email_change
    FOR EACH STATEMENT EXECUTE FUNCTION require_shared_migration_gate();
CREATE TRIGGER migration_write_gate BEFORE INSERT OR UPDATE OR DELETE ON email_challenge
    FOR EACH STATEMENT EXECUTE FUNCTION require_shared_migration_gate();
CREATE TRIGGER zz_identity_change_gate BEFORE INSERT OR UPDATE OR DELETE ON pending_email_change
    FOR EACH STATEMENT EXECUTE FUNCTION serialize_identity_changes();
CREATE TRIGGER zz_identity_change_gate BEFORE INSERT OR UPDATE OR DELETE ON email_challenge
    FOR EACH STATEMENT EXECUTE FUNCTION serialize_identity_changes();

CREATE FUNCTION purge_ineligible_email_storage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.account_state IN ('ERASED','IMPORTED_INACTIVE','SUSPENDED') THEN
        DELETE FROM email_challenge WHERE subject_id=NEW.id;
        DELETE FROM pending_email_change WHERE subject_id=NEW.id;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER app_user_email_storage AFTER UPDATE OF account_state ON app_user
    FOR EACH ROW EXECUTE FUNCTION purge_ineligible_email_storage();
-- V18 last-administrator and irreversible-erasure triggers intentionally remain intact.
