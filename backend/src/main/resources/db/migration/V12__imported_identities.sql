ALTER TABLE app_user ADD COLUMN account_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE app_user ALTER COLUMN email DROP NOT NULL;
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_account_state CHECK (
    (account_state = 'ACTIVE' AND email IS NOT NULL AND password_hash IS NOT NULL)
    OR (account_state = 'IMPORTED_INACTIVE' AND email IS NULL AND password_hash IS NULL AND role = 'MEMBER')
);

CREATE OR REPLACE FUNCTION advance_auth_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.role IS DISTINCT FROM OLD.role OR NEW.password_hash IS DISTINCT FROM OLD.password_hash
        OR NEW.email IS DISTINCT FROM OLD.email OR NEW.account_state IS DISTINCT FROM OLD.account_state THEN
        NEW.auth_revision := OLD.auth_revision + 1;
    ELSE
        NEW.auth_revision := OLD.auth_revision;
    END IF;
    RETURN NEW;
END;
$$;

-- Durable attribution, independent of expiring transfer jobs. Never queried by login.
CREATE TABLE imported_author (
    source_instance_id UUID NOT NULL,
    source_user_id UUID NOT NULL,
    local_user_id UUID NOT NULL UNIQUE REFERENCES app_user(id),
    source_email VARCHAR(254),
    PRIMARY KEY(source_instance_id, source_user_id)
);

CREATE FUNCTION protect_imported_author() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Imported author provenance is immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER imported_author_immutable BEFORE UPDATE ON imported_author
    FOR EACH ROW EXECUTE FUNCTION protect_imported_author();

-- Claiming is intentionally unavailable until a verified identity workflow exists.
CREATE FUNCTION prevent_imported_account_activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.account_state = 'IMPORTED_INACTIVE' AND NEW.account_state <> OLD.account_state THEN
        RAISE EXCEPTION 'Imported account claiming is unavailable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER app_user_imported_state BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION prevent_imported_account_activation();
