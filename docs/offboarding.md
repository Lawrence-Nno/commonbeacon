# Explicit account deletion and company erasure

This feature supports one company and one backend process per deployment. It is
separate from export, import, retention cleanup, and removal of a deployment.
Export is offered first but is optional. A successful download never authorizes
deletion. These are technical retention choices, not certification of compliance
with any jurisdiction; review legal and contractual obligations separately.

## Disclosed scope

**Delete my account** (`/account/delete`) is available to an authenticated ACTIVE
account with its current password. Confirmation removes its email and password,
replaces its display name with `Deleted member`, removes authority, and makes the
ERASED state irreversible. It invalidates the confirming session. All other old
sessions lose access, including if the same email is later registered to a new
UUID. The last ACTIVE administrator cannot delete or demote itself; another
usable administrator must first exist. SQL identity changes are serialized and a
database trigger enforces this rule for updates/deletes, including concurrent ones.

The account UUID and creation timestamp remain for relational integrity. Questions,
replies, articles, accepted answers, visibility and other people's contributions
remain. Text can still identify its author: this is pseudonymization, not a promise
of complete anonymity. Edit identifying contribution text beforehand if needed.
The requester's structured report reasons, moderation-action reasons and report
resolution notes are replaced with a fixed removal message. Other people's report
text and decisions remain. Imported inactive authors cannot authenticate or use this
flow; neither an imported email nor possession of an archive proves ownership.
Verified claiming and third-party identity requests remain separate work.

**Erase company data** (`/admin/erase`) requires an ACTIVE administrator, its password,
deployment opt-in `COMPANY_ERASURE_ENABLED=true` (default false), and the exact
phrase `ERASE COMPANY <source-instance-UUID>`. It removes all community records,
other accounts, imported contact/provenance rows, and transfer records in this
database. The requesting administrator remains ACTIVE to manage the empty instance.
Installation settings, instance UUID, schema migrations, and minimal erasure records
remain. This is a company data reset, not account deletion for the retained operator
or destruction of containers, volumes, infrastructure, logs or backups. Disable the
opt-in after use. Existing administrator authority is the ownership boundary; there
is no separate verified company-owner role.

**Both operations** pause community API reads/writes and transfer work, cancel
unfinished transfers, and delete every artifact UUID registered in this database,
including other users' exports, uploaded archives, staging and detailed import
reviews. Company archives can contain the deleted person's data, so the preview
requires explicit acknowledgement of this community-wide impact. Each file is
deleted through the configured private store; arbitrary paths and unregistered
files are not scanned by this job. Normal orphan reconciliation still applies.
Downloaded files, third-party copies, reverse-proxy/browser caches and external
backups cannot be recalled. Already delivered responses cannot be erased remotely.

Account erasure preserves completed import provenance and numeric completion counts,
while stripping detailed activation reviews, request records, audit/attempt history
and file references. Existing imported source contacts belong to inactive source
identities and are not matched to an ACTIVE requester by email. Company erasure
removes those contacts. Temporary previews expire after five minutes. Capability
status records expire 30 days after confirmation (running work is never expired).
The minimal tombstone ledger retains only job, instance and subject UUIDs, scope,
confirmation time and backup-expiry deadline. It is deliberately retained to
suppress restoration of erased data; these identifiers are pseudonymous data.

## Confirmation and recovery

1. Review the impact preview. Account previews expose only own private counts;
   company previews contain instance-wide counts. A target generation, transfer
   fingerprint, requester and instance bind the preview digest.
2. Check all four acknowledgements and type the exact phrase. Confirm the current
   password for the single-use `ACCOUNT_ERASURE` or `COMPANY_ERASURE` grant. Existing
   export/download grants cannot authorize deletion. CSRF is required; JSON is
   limited to 4096 bytes and unknown fields are rejected.
3. Confirmation drains admitted HTTP responses and takes the PostgreSQL migration
   gate. Active worker/download leases and committing imports reject confirmation.
   Concurrent changes produce a stale-preview or busy response; review again.
4. A durable RUNNING job and tombstone commit together. Account credentials are
   removed in that transaction. After confirmation there is no cancel or undo.
   Worker phases commit at most 200 rows, or delete one registered file outside the
   database lock. The job resumes from persisted phase after process/database restart.
   Failed work retries after ten seconds and keeps the maintenance barrier closed.
5. Save the private status receipt before leaving. The browser keeps its random
   token only in memory; the database stores its SHA-256 hash. A lost confirmation
   response switches to status lookup, never automatic resubmission. Restore a
   saved ID/token at `/erasure/receipt`. A missing/expired receipt means the outcome
   is unknown; it does not mean erasure was undone. The receipt cannot authorize
   deletion or reveal names/content. Status is `no-store` and the secret travels
   in `X-Erasure-Receipt`, never the URL.

While maintenance is active, API traffic returns `503 ERASURE_IN_PROGRESS` with
`Retry-After: 5`; health, CSRF bootstrap and capability status remain available.
The in-process admission lock drains responses in the supported **single-backend**
deployment. Database gates also block ordinary writers. Multiple backend processes,
direct SQL writers, external readers and administrative TRUNCATE operations require
separate operator maintenance coordination and are not covered by that HTTP drain.
Demo seeding is permanently skipped once any tombstone exists, including during
restart recovery. Readiness health indicates process availability, not permission
to reopen a restored installation to traffic.

## Backup expiry and restore suppression

`ERASURE_BACKUP_RETENTION_DAYS` (1–365, default 30) is an operator-enforced promise,
shown before confirmation and saved as `backup_purge_after`. The application does
not control your backup provider. Configure actual database, WAL/PITR, artifact,
snapshot and replica retention to meet it, and verify deletion on the deadline.
Do not select a shorter number than the infrastructure can meet. Do not use a
legal hold or a longer provider retention period while promising this deadline.

Keep the newest erasure ledger independently of older restorable backups, protected
with the same access controls as other private operational data. Export it after
confirmations and as part of the backup process, and preserve subsequent ledger
changes through your durability/replication policy. A stale or lost ledger cannot
suppress deletions it never recorded; keep restoration offline until that gap is
resolved. Full disaster-recovery certification remains Stage 16 work.

From a private backup working directory, with PostgreSQL connection credentials
supplied through the operator's normal secure mechanism:

```text
psql -X -v ON_ERROR_STOP=1 -f <repo>/scripts/export-erasure-ledger.sql
```

This writes `erasure-ledger.csv` in the current directory. Archive it privately
outside the snapshot rotation that could restore older data. Do not check it into
source control. The export command overwrites that filename; use a fresh private
directory when preserving multiple ledger versions.

To restore, keep the application stopped and all public/external database readers
disconnected. Restore the same instance database and migrate to V18 or later with
demo seeding disabled and workers/network isolated. Retain its original
`source_instance_id`; do not change that UUID to bypass a ledger mismatch. Place
the latest separately saved ledger in a private working directory and run:

```text
psql -X -v ON_ERROR_STOP=1 -f <repo>/scripts/restore-erasure-ledger.sql
```

The script imports the complete ledger in one transaction. `queue_restored_erasure`
rejects a different instance or conflicting tombstone, skips tombstones already
present in the snapshot, and queues missing confirmations. A RUNNING job already
in the backup continues normally. Pending restore jobs keep the barrier closed;
the worker processes them in confirmation order. Re-importing the ledger does
not erase new data a second time. Company replay requires the original requesting
administrator UUID to exist and be ACTIVE; if it was created after the backup,
provision a verified recovery account with that UUID and fresh credentials offline
before importing. Account replay that would lose the last administrator fails
closed until an operator provisions another legitimate administrator offline.
Do not forge passwords, assume email ownership, or disable the last-admin trigger.

Restore artifact storage into this instance's own private directory. Do not share
one writable store between independent instance databases. The worker deletes only
registered UUIDs; inventories, unregistered old files and external snapshots need
operator reconciliation before reopening service. Start one backend behind the
closed maintenance boundary. Wait until no `RUNNING` or `RESTORE_QUEUED` jobs remain,
verify retained/erased records and storage, and only then reopen traffic. If the
latest ledger is unavailable, a same-instance check fails, or verification differs,
leave service closed. A backup restored without this procedure can reintroduce data.

## Operator failure handling

Inspect only minimal job metadata (`id,scope,state,phase,processed,error_code,
next_attempt_at`) and safe `erasure.retry_required` logs. Work-unit counts are
progress, not exact person counts or proof of external backup deletion. Restore
database/storage availability and allow the scheduled worker to retry. Keep the
registered artifact metadata until deletion succeeds. Never manually mark a failed
job complete, remove the barrier, delete volumes, sweep arbitrary directories, or
alter the instance UUID as a recovery shortcut. There is no user-facing force or
cancel endpoint. The runtime worker switch is a test/maintenance property, not a
way to bypass an unfinished job.
