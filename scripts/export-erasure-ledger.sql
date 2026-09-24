-- psql -X -v ON_ERROR_STOP=1 -f <absolute-path>/scripts/export-erasure-ledger.sql
-- Run from a private backup directory. The output contains pseudonymous identifiers.
\set ON_ERROR_STOP on
\copy (SELECT job_id,instance_id,subject_id,scope,confirmed_at,backup_purge_after FROM erasure_tombstone ORDER BY confirmed_at,job_id) TO 'erasure-ledger.csv' WITH (FORMAT csv,HEADER true)
