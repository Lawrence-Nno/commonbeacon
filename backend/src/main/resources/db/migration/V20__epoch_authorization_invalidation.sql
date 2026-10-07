-- An explicit epoch advance must invalidate durable transfer/grant authorization too.
-- Normal security changes still advance the independent counters exactly once.
CREATE OR REPLACE FUNCTION advance_auth_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.role IS DISTINCT FROM OLD.role OR NEW.password_hash IS DISTINCT FROM OLD.password_hash
        OR NEW.email IS DISTINCT FROM OLD.email OR NEW.account_state IS DISTINCT FROM OLD.account_state
        OR NEW.email_verified_at IS DISTINCT FROM OLD.email_verified_at OR NEW.auth_epoch > OLD.auth_epoch THEN
        NEW.auth_revision := OLD.auth_revision + 1;
        NEW.auth_epoch := greatest(OLD.auth_epoch + 1, NEW.auth_epoch);
    ELSE
        NEW.auth_revision := OLD.auth_revision;
        NEW.auth_epoch := OLD.auth_epoch;
    END IF;
    RETURN NEW;
END $$;
