ALTER TABLE app_user ADD COLUMN auth_revision BIGINT NOT NULL DEFAULT 0 CHECK (auth_revision >= 0);
CREATE FUNCTION advance_auth_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.role IS DISTINCT FROM OLD.role OR NEW.password_hash IS DISTINCT FROM OLD.password_hash
        OR NEW.email IS DISTINCT FROM OLD.email THEN
        NEW.auth_revision := OLD.auth_revision + 1;
    ELSE
        NEW.auth_revision := OLD.auth_revision;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER app_user_auth_revision BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION advance_auth_revision();
ALTER TABLE transfer_job ADD COLUMN authorization_revision BIGINT NOT NULL DEFAULT 0 CHECK (authorization_revision >= 0);
UPDATE transfer_job j SET authorization_revision=u.auth_revision FROM app_user u WHERE u.id=j.requester_id;
ALTER TABLE transfer_request DROP CONSTRAINT transfer_request_operation_check;
ALTER TABLE transfer_request ADD CONSTRAINT transfer_request_operation_check
    CHECK (operation IN ('COMPANY_EXPORT','PERSONAL_EXPORT','COMPANY_IMPORT','CANCEL'));
CREATE TABLE transfer_download (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    artifact_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '10 minutes',
    finished_at TIMESTAMPTZ,
    FOREIGN KEY(job_id,artifact_id) REFERENCES transfer_artifact(job_id,id)
);
CREATE INDEX ix_transfer_download_active ON transfer_download(artifact_id,expires_at) WHERE finished_at IS NULL;
