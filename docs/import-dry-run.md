# Import staging and dry-run review

Stage 10 adds backend-only native import staging. It does not activate data or add
an import screen (Stages 11 and 12). Upload and inspection follow the
[quarantine guide](quarantine-upload.md). Neither stage writes community records,
active accounts, imported-author identities, roles, sessions or moderation history.

## API workflow

After inspection, POST `/api/v1/admin/data/imports/{id}/dry-run` with
`{"expectedVersion": <current job version>}`, a fresh UUID `Idempotency-Key`, session
cookie and CSRF header. The response is 202 VALIDATING. A matching key/body replays;
a reused key with another job/version or stale expected version returns 409. Current
ACTIVE administrator authority, unchanged account authorization revision and job
ownership are checked. No additional password grant is consumed for a dry run.

Poll GET `/api/v1/admin/data/imports/{id}/review`; 409 means no completed review yet.
All responses are `Cache-Control: no-store`. The report includes:

- Archive SHA-256, source instance/profile/format, validation and mapping versions,
  mapping revision, entity counts and checkpoints, fixed retention deadline.
- Up to 1,000 safe file/line/code errors and a total error count; no raw bodies,
  source email addresses, display names, reasons or moderation notes.
- Fixed warning/acknowledgement codes for inactive imported authors, private content,
  separate identities despite matching IDs/emails, optional omitted files and
  preserved imported moderation provenance. Optional exclusions are not graph errors.
- A maximum of 100 source-to-local ID previews. The complete mappings stay private.
  Contacts refer to mapped users; acceptances refer to mapped questions/replies.
- Target generation/state fingerprint, record and byte budgets, staging/mapping
  fingerprint, and a review fingerprint binding those inputs and the warnings.

An error-free review on an eligible target becomes READY_TO_COMMIT; other reviews
remain REVIEW_REQUIRED. `activationAvailable` is always false. There is no confirm
endpoint in Stage 10. A later activation must check the reviewed digest, accept all
required acknowledgements, and revalidate under the Stage 11 exclusive migration
gate. Never treat READY_TO_COMMIT alone as authority to insert data.

## Validation and identity rules

The worker rehashes the private ZIP and repeats strict native-v1 schema, file-set,
integrity and full graph validation. Checks include required authors/references,
acceptances belonging to the right question and a visible reply, unique slugs,
article publication lifecycle, report targets/resolution, timestamp ordering and
unique effective origins within each entity. Unsupported archive entries, including
attachments, are rejected. Native import does not silently discard records.

Local IDs are deterministic for job/entity/source ID. A different job receives
separate mappings. Existing local ID collisions block review. Matching source IDs
or contact emails only warn: they never merge identities, claim existing accounts,
restore privileges or make an imported author sign-in capable. Source origin and
historical action payloads remain in private staging for the later activation phase.

An eligible target has no boards, questions, replies, articles, reports or actions.
Only ACTIVE bootstrap ADMINISTRATOR accounts may exist, at most 2,000; demo seeding
must be disabled. A MEMBER, MODERATOR or imported inactive account blocks eligibility.
The seeded development installation is intentionally ineligible.

## Bounded work, recovery and retention

V15 adds transfer-owned `transfer_dry_run` and `transfer_stage` tables and target
mutation triggers. It preserves existing uploads, inspections and community rows.
Staging batches are at most 64 rows / 1 MiB of serialized payload, with 256 KiB per
record, 40,000 total records, 256 MiB total staged payload and native entity limits.
The worker shares the deployment-wide transfer lease, heartbeats during reads, has
a ten-minute read/validation deadline and at most three claims per dry-run attempt.
Final database checks use short ten-second transactions; unsupported workloads fail
closed. Production transaction, WAL, index and throughput capacity is **not certified**
by these configured ceilings and must be measured before enabling activation.

Recovery replays the immutable archive from the start and checks each existing
payload and mapping rather than inserting duplicates. Per-entity staged line
checkpoints are included in the report; job checkpoint shows unique staged rows
while running. Database payload scans use cursor batches of 16, not a full archive
buffer. Provisional staged records never become live when later graph checks fail.

Staging and its source archive expire 24 hours after the first dry-run request.
Repeated reviews do not extend this deadline. Cancelled, failed and expired jobs
have staging, review and mappings purged by the existing 15-minute reconciler.
The source archive is also eligible for deletion after cancellation/failure/completion;
its reservation is released after confirmed deletion. Cancellation never exposes it. No public repositories or search paths query staging.

Every domain/identity INSERT, UPDATE, DELETE or TRUNCATE advances a sequence.
Rollbacks and no-op writes conservatively invalidate reviews too. This avoids adding
a shared counter-row lock after existing domain locks. Review reads compare both
sequence and target state to cover transactions that committed after a sequence
increment. They also recompute staging/mapping digests and inspect source metadata;
changed inputs mark the report stale and remove READY_TO_COMMIT. A new dry run is
required. A review read is not an atomic guarantee against subsequent writes: the
Stage 11 gate and final revalidation are mandatory before any activation.

The dry-run worker runs when private storage is enabled. Set
`commonbeacon.transfer.import.dry-run.enabled=false` to pause it independently of
the inspection/export workers. Structured logs carry job IDs and safe failure
categories, never staged payloads.
