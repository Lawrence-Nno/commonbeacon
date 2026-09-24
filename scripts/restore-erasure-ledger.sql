-- OFFLINE ONLY: restore and migrate the same instance to V18 first. Keep application
-- traffic closed. Read docs/offboarding.md; use the newest independently saved ledger.
-- psql -X -v ON_ERROR_STOP=1 -f <absolute-path>/scripts/restore-erasure-ledger.sql
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE restore_erasure_input (LIKE erasure_tombstone INCLUDING CONSTRAINTS) ON COMMIT DROP;
\copy restore_erasure_input(job_id,instance_id,subject_id,scope,confirmed_at,backup_purge_after) FROM 'erasure-ledger.csv' WITH (FORMAT csv,HEADER true)
SELECT queue_restored_erasure(job_id,instance_id,subject_id,scope,confirmed_at,backup_purge_after)
FROM restore_erasure_input ORDER BY confirmed_at,job_id;
COMMIT;
SELECT state,count(*) FROM erasure_job WHERE state IN ('RESTORE_QUEUED','RUNNING') GROUP BY state;
