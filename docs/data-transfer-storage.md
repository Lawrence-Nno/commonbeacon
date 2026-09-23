# Transfer jobs, private storage and recovery

CommonBeacon has internal durable-job and private-artifact infrastructure for data
transfers. [Requester access and protected downloads](data-transfer-access.md) are
implemented, including [company export](data-transfer-access.md#company-export)
and [personal export](personal-export.md), plus [quarantined upload and inspection](quarantine-upload.md).
[Bounded native activation](import-activation.md) and [transfer retention/operations](transfer-operations.md) are implemented. These classes
are internal building blocks; accepting an actor UUID in a Java method is not an
HTTP authorization mechanism.

## Durable metadata

V10 adds transfer jobs, idempotency records, worker attempts, artifacts, staging
mappings, completion records and metadata-only audit events. It does not rewrite
existing community tables. Job kinds separate company export, personal export and
company import. Database checks constrain each kind's allowed states; artifact
expiry/deletion is separate from a job's retained completion outcome.

Creation persists the requester, kind, request digest and intent before work runs.
Idempotency keys are scoped by requester and operation for 24 hours: matching
digests return the original job, while mismatches fail. Request adapters
calculate the digest from the canonical logical request, excluding transient
authentication grants. V13 stores company export options alongside each job and
a stable source-instance UUID. V14 adds bounded inspection results and upload/inspection
audit events. V15 adds private staging, deterministic mappings and [dry-run review](import-dry-run.md). V16 adds confirmation, atomic activation and immutable imported provenance.

Short READ COMMITTED transactions acquire the shared migration gate, then lock the singleton coordination row, then the
job and requester as needed. All foundation mutations use this order; file I/O
runs outside coordination transactions; each export extraction holds its
read-only database snapshot while writing the bounded intermediate file. Existing
domain services acquire the shared migration gate but do not acquire this
transfer lock. Atomic live import takes the exclusive migration gate. Row locking follows PostgreSQL's
[transaction locking rules](https://www.postgresql.org/docs/18/applevel-consistency.html).

The internal job methods check requester ownership and current database roles.
Company work requires ADMINISTRATOR; personal export is requester-specific.
There is no takeover by another administrator. HTTP/session/CSRF and scoped recent-authentication controls protect the implemented
job and download routes. V11 pins worker authorization revisions to detect revocation. V12 requires ACTIVE
accounts in addition to roles, including worker and current-session checks.

## Worker leases and publication

There is at most one live worker lease across the deployment. Claims, heartbeat,
checkpoint, completion and cancellation coordinate through PostgreSQL, so a second
JVM cannot independently claim the same work. Leases last 60 seconds; the export
worker heartbeats every 15 seconds. Claims increment a fencing token and attempts
are capped at three. Expired export attempts restart their snapshot checkpoint at
zero; validation mappings/checkpoints can survive for later immutable-input handling.

Checkpoint updates cannot move backwards. Old fences, expired leases, cancelled
jobs and revoked roles cannot publish or advance progress. Revoked authorization
becomes a terminal failure, not an endless retry. Queue expiry is 30 minutes;
initial uploads expire after one hour; executing attempts have a ten-minute budget.
Producer I/O must support cancellation/timeouts; the worker cannot forcibly stop
arbitrary blocking third-party code. The shared export runner selects dedicated
company or personal projections by job kind. It is registered when storage is
enabled; its scheduled polling interval is five seconds.

The export runner commits artifact intent before asking a producer to write.
It verifies the finalized file and atomically records availability, READY state,
the completion ledger and audit event. `TransferJobs.publish` is the low-level
metadata operation: callers must use the verified-store path, as the export runner
does. Repeating a successful publication with the original lease/artifact returns
the original result without a second completion event. An old worker cannot
publish after a replacement claim. Authenticated requester-only download routes serve existing completed artifacts.
Download artifacts expire 24 hours after successful publication. Missing or
corrupted artifacts retain the READY outcome with an `ARTIFACT_MISSING` diagnostic.

The quarantine upload handler and inspector use UPLOADING, UPLOADED, VALIDATING
and REVIEW_REQUIRED. Inspection never transitions to READY_TO_COMMIT. The export
runner does not claim imports; inspection and uploads share its global lease. COMMITTING work is never automatically replayed after expiry: it is
flagged for activation reconciliation. Live-domain transactions and confirmation
must be implemented before that state can be used by a product workflow.

## Private local store

Storage and its schedulers are disabled by default. The backend accepts these
environment settings:

```text
TRANSFER_STORAGE_ENABLED=true
TRANSFER_STORAGE_DIRECTORY=/var/lib/commonbeacon/transfers
```

Use an absolute, dedicated directory outside the backend's working/application
tree. Windows hosts may use a dedicated absolute directory on a private data drive.
Do not point the setting at a home directory, shared temporary directory or web
root. The store rejects relative/application paths, symlinked/canonicalized paths,
and unrelated non-empty directories before changing their permissions.

Files have generated UUID keys, never source filenames or supplied paths. POSIX
directories/files use 0700/0600; Windows uses an owner-only ACL. The store refuses
filesystems without either permission model. Reads and deletion reject symlinks
and non-regular artifact paths. Existing unrelated files are never cleanup targets.

Writes use private `.part` files, byte limits, flush-to-disk, and same-directory
atomic rename to `.blob`. There is no fallback to a non-atomic move. Final keys
cannot be overwritten. Cooperating processes serialize filesystem mutations with
a lock file; all replicas must see the same durable directory and support file
locking/atomic rename. See Java's [file operation contracts](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html).

The default deployment-wide artifact quota is 2 GiB with a 1 GiB free-space floor.
Job admission reserves 768 MiB of working space per active job. Once a job is
terminal and every remaining artifact is a published download with a known size,
its reservation shrinks to the total retained download bytes. Temporary, missing
or deleting artifacts prevent this reduction until deletion is confirmed. This
reconciliation runs before admission, after confirmed cleanup, and in scheduled
reconciliation, including for exports created before this accounting change.
Each job has at most ten unfinished
or available artifact records and aggregate allocated limits of 768 MiB. Individual
intermediate files cap at 256 MiB and downloads at 64 MiB. Filesystem checks include
existing bytes before new writes; streamed writes enforce their declared byte cap.
The quota can therefore admit fewer jobs than the ten-job queue ceiling. There is
one active job per requester. Job-list requests are bounded to 100 records.

For the supplied local Compose deployment, opt in with:

```powershell
docker compose -f compose.yaml -f compose.transfers.yaml up -d --build
```

The overlay enables the worker and mounts a dedicated `transfer_artifacts` volume
at the backend-owned 0700 directory. Use both files on subsequent up/down commands;
ordinary down retains the volume. The base Compose deployment remains storage-off.
For other deployments, mount a durable private directory writable by the service
identity. An ephemeral container layer is unsuitable. Company and personal screens
are described in the [administrator](data-management.md) and [personal](personal-export.md) guides.
Disabling `commonbeacon.transfer.export.worker.enabled` pauses automatic export
processing for operator maintenance; queued jobs still obey their expiry limits.

Artifact bytes are **not encrypted by this Java implementation**. Production use
requires an encrypted filesystem/volume and encrypted backups with keys managed
outside the archives; database metadata and backups also need appropriate access
and encryption controls. File permissions do not provide encryption. Verify restore,
power-loss durability and target-filesystem behavior on the chosen infrastructure.

## Reconciliation and audit

When storage is enabled, reconciliation runs on startup and every 15 minutes.
It handles missing/corrupted finalized files, expired artifacts, abandoned writes,
lease loss and recognized orphan files. Orphans have a 15-minute grace period.
Unknown filenames are not deleted. Failed physical cleanup remains retryable;
availability is revoked before deletion is attempted. Terminal job reservations
retain the bytes of downloadable archives until their files are deleted; unused
working space is released as described above. Failed staging mappings are removed. A missing file never causes a successfully completed export to rerun.

A crash after rename but before the database publication transaction leaves an
unavailable artifact. Recovery fences that attempt and removes the abandoned file;
it does not infer success from the presence of a `.blob`. Completion records remain
after artifact expiry. Terminal audits and attempts expire after 30 days; ordinary
job metadata is purged only after its files, request records and audit history are
gone. Completed import receipts and provenance have a lifetime exception. See the
[retention policy and runbooks](transfer-operations.md) for exact bounds and metrics.

Audit records contain a job reference, fixed event name and timestamp. Requester
and worker attribution comes from the job and attempt records. Events distinguish
download attempts from completed server delivery; handlers record completion only
after writing the verified response. Active download and worker leases delay physical cleanup.
Confirmation records its own event. Events contain no content bodies, passwords,
authentication grants, supplied paths or raw exception messages. Reconciliation
logs a structured failure event with exception types and application code locations,
excluding raw exception messages (see [backend logging](logging.md)). This is not a tamper-proof external audit log.

## Verification

`TransferFoundationIT` exercises competing/recreated workers, durable idempotency,
lease recovery, fencing, permissions, quota admission, mappings, publication replay,
interrupted work, orphan cleanup, corruption, expiry and retried deletion using
disposable PostgreSQL and temporary files. `LocalArtifactStoreTest` checks immutable
publication, file permissions, quotas and unsafe-directory handling. Migration tests
verify fresh V11 startup and preservation of populated V5 and V9 domain data.

These are functional tests, not production throughput or power-failure benchmarks.
Large-data, multi-host storage and end-to-end transfer verification remain necessary
before release. See the [archive format](data-archive-format.md) for entry validation.
