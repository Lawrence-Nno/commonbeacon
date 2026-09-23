# Transfer retention and operations

With private storage enabled, a startup sweep and a sweep every 15 minutes recover
expired jobs, revoke unavailable artifacts, remove temporary data, and reclaim
reservations. Cleanup never deletes community records or imported provenance.
There is no post-success rollback: edits after import require a separate, explicitly
designed offboarding workflow. Export and download never request deletion.

## Retention policy

- Downloads expire 24 hours after publication. Access checks enforce expiry even
  when disk deletion fails. A current download lease can delay physical deletion,
  but cannot authorize a new expired download.
- Unfinished uploads expire after one hour. Working jobs retain their existing
  deadlines and fencing rules. A dry run and its upload expire 24 hours after the
  first dry-run request; rerunning validation cannot extend that retention.
- Terminal import uploads, staging payloads, temporary mappings, dry-run reports,
  and inspection errors are removed at the next successful sweep. Inspection and
  review reads enforce their expiry independently of physical cleanup. Unconfirmed
  reconciliation details may become unavailable; that does not change the outcome.
- Recognized orphan `.part`/`.blob` keys have a 15-minute grace period. Unknown
  filenames are left alone. Only generated UUID keys inside the configured private
  root can be deleted; no HTTP cleanup path accepts a filesystem path.
- Ordinary request idempotency lasts 24 hours. Expired request rows and expired
  download leases are swept separately from jobs. Expired downloads cannot resume.
- Terminal audit events and finished worker attempts are retained for 30 days.
  Export/failed/cancelled job metadata is eligible for removal after 30 days, once
  all files are confirmed deleted, reservations are zero, request/download records
  have expired, and its audit events have aged out. A later cleanup event can extend
  this period. A failed deletion never causes the owning metadata to be discarded.
- **Completed imports retain their job, activation receipt, completion marker and
  immutable source mappings for the lifetime of the imported data.** They are
  required for attribution, round-trip export and historical reconciliation.
  Confirmed receipts include the bounded review metadata and mapping preview, not
  staged bodies or source contact values. Confirmation replay remains tied to that
  receipt. Audit events and attempts still expire after 30 days. Failed confirmation
  receipts last until their ordinary job metadata is removed.

Sweeps process at most 10,000 artifact candidates (expired/deleting first), 100
terminal staging jobs, 1,000 rows per metadata category and 100 old jobs per pass.
Large backlogs can require multiple passes. Database mutation transactions retain
their ten-second timeout. Failed passes retry on the next schedule or backend start.
Backups and operator log retention are independent; cleanup cannot erase historical
backups or downloaded copies. This policy is not an account-deletion workflow.

## Diagnostics

Every completed sweep attempt emits `transfer.storage_snapshot` with numeric
`reserved_bytes`, `cleanup_backlog`, `staged_bytes`, `stale_jobs`, `active_jobs`,
`used_bytes`, `quota_bytes`, `usable_bytes`, `minimum_free_bytes`, and
`cleanupComplete`. Storage values may be absent when storage inspection fails.
`used_bytes` measures files in the private root; `usable_bytes` is filesystem-wide
free space. `reserved_bytes` is admission accounting, not measured disk usage.
`cleanup_backlog` counts nondeleted expired, missing or deleting artifact records;
it includes files temporarily protected by downloads or workers.

The same cached values are Micrometer gauges prefixed `commonbeacon.transfer.`.
Unknown/not-yet-sampled values are `-1`. Additional gauges are
`cleanup_last_success_seconds` (Unix epoch; zero until success) and
`cleanup_failures` (failed passes since process start). Values refresh on each sweep,
not on every scrape. A failed pass does not advance last success. Database failures
can leave the prior snapshot unchanged. No job IDs or paths are metric labels.

Only health remains exposed through HTTP. The application registers these meters
for an operator's separately secured metrics integration; it does not add a public
metrics endpoint, collector, dashboard or alert-delivery service. Structured logs
work with the supplied Compose stack:

```powershell
docker compose -f compose.yaml -f compose.transfers.yaml logs --since 1h --tail 500 backend |
  Select-String 'transfer.storage_snapshot|transfer.cleanup_failed|transfer.reconciliation_failed|transfer.storage_scan_failed|transfer.orphan_cleanup_failed|transfer.storage_metrics_failed'
```

Per-artifact failures emit `transfer.cleanup_failed` with the owning job ID.
Exceptions include allowlisted types and application locations only; no raw
messages, supplied paths, filenames, contact values or content. One broken file
does not block unrelated files or database housekeeping. Root-scan failure still
permits database housekeeping. A database outage may stop the pass entirely.

## Disk pressure

Compare used bytes with the 2 GiB quota, filesystem free bytes with the 1 GiB
floor, and reservations with the admission quota. Investigate at 80% quota usage,
free space approaching the floor, or a backlog growing across two sweeps. These
are suggested alert thresholds, not a tested production capacity claim.

If legitimate downloads or live jobs hold space, wait for their bounded retention
or cancel an owned cancellable job through its normal UI. If deletion is failing,
check the mounted volume's service ownership, permissions, read-only status and
host free space. Restore storage access and allow the next sweep to retry. Do not
raise archive limits to hide pressure, edit artifact states, remove database rows,
or manually delete a live job's files. Never run `docker compose down -v`, volume
pruning, or recursive directory deletion as a transfer cleanup procedure.

## Missing or corrupt artifact

Use the job ID to correlate logs, status and audit history. A missing/corrupt
published artifact is revoked and marked with `ARTIFACT_MISSING`; an export's
historical READY outcome is retained. Request a new export if needed. For imports,
inspect the authoritative state and start a new import only after a failed outcome
is established. Do not substitute another file under an existing artifact key.
Check durable-volume mounting and storage health before retrying.

## Stuck job or failed sweep

Investigate `stale_jobs > 0`, a nonzero failure count increase, or no successful
sweep for more than 30 minutes. Check backend health, PostgreSQL connectivity,
the write gate, scheduled-worker configuration, and storage errors. Leases expire
after 60 seconds and recover through fencing; export attempts restart their
snapshot, while staging can resume. Do not manually clear leases or change job
states. COMMITTING closes cancellation; check its durable outcome before starting
another migration. A committed receipt must never be removed to force a retry.

After correcting the cause, wait for the next sweep or perform a normal backend
restart with the same database and artifact mounts. Restart invalidates sessions.
Confirm that last success advances, backlog/reservations fall as expected, and
job outcomes remain available. A continued failure needs operator investigation;
there is no user-facing arbitrary cleanup or force-success endpoint.
