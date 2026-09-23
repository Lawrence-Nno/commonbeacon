# Bounded atomic native activation

Stage 11 adds backend activation for native company archives. Upload, inspection
and [dry-run review](import-dry-run.md) remain separate. The [browser import workflow](import-ui.md) is available in Data management. The seeded development community is not an eligible import target.

## Confirm and observe

An eligible target contains only ACTIVE bootstrap ADMINISTRATOR accounts (at most
2,000), no imported identities/provenance and no community content. Disable demo
seeding. A fresh validation-version-2 review must have no errors and fit the
activation envelope: 40,000 source records, native per-entity limits and **16 MiB
of serialized staged JSON**. The ZIP still has its separate 64 MiB ceiling.

Obtain a fresh password grant with scope `IMPORT_COMMIT`, then POST
`/api/v1/admin/data/imports/{id}/confirm` with session cookie, CSRF header, UUID
`Idempotency-Key` and this body (substitute values from the current job/review):

```json
{
  "expectedVersion": 12,
  "reviewDigest": "<64-character review SHA-256>",
  "archiveDigest": "<64-character archive SHA-256>",
  "targetGeneration": 8,
  "acknowledgedPrivateContent": true,
  "acknowledgedInactiveAuthors": true,
  "acknowledgedWarnings": ["IMPORTED_AUTHORS_INACTIVE", "IMPORTED_HISTORY_PROVENANCE"],
  "recentAuthGrant": "<IMPORT_COMMIT grant>"
}
```

`acknowledgedWarnings` must equal the actual review's warning set, including any
identity collision or omitted-file warnings. The example set is not universal.
The requester must still be an ACTIVE administrator with the same authorization
revision. A successful response is **202 COMMITTING**, not import success.
Poll the existing job endpoint until COMPLETED or FAILED. All responses are no-store.

Confirmation stores its intent before the worker begins. Cancellation is accepted
only before COMMITTING and returns 409 thereafter. Retrying the same key and
confirmation fields returns authoritative job state without consuming another
grant or inserting records again. A different key/body conflicts. The grant itself
is excluded from the idempotency digest and is never persisted. Confirmation expires
after at most five minutes; activation does not silently restart abandoned commits.

## Atomic publication and access

The worker claims a fenced 60-second lease and obtains an exclusive PostgreSQL
transaction advisory gate. It rechecks authority, lease, target generation/state,
review/manifest/staging/mapping digests, source artifact metadata, actual ZIP digest,
warning-bound confirmation and budgets before inserting domain data.

One transaction inserts inactive users and author metadata, boards, questions without
acceptance, replies, acceptance links, articles, optional reports/actions, immutable
provenance, completion ledger and COMPLETED state. Any pre-commit failure rolls all
of them back. Readers cannot observe intermediate entity groups. Existing snapshot
readers may continue seeing their earlier empty snapshot after commit.

Source lifecycle/visibility, timestamps, references and supported text are retained.
PostgreSQL rebuilds search vectors. Local optimistic versions start at zero and are
not portable. No ordinary mutation services run during insertion, so activation
does not invent local moderation actions or replace source timestamps.

All imported users have `IMPORTED_INACTIVE`, MEMBER role, no login email and no
password hash. Contacts live only in `imported_author.source_email`. Matching a
bootstrap email or source ID does not merge accounts or confer authority. Bootstrap
accounts keep their credentials and access. Historical actor roles grant nothing.

`imported_record` is the immutable provenance/mapping ledger for the seven entities
with their own IDs. It stores immediate source-to-local mapping plus effective
origin instance/source ID. Contacts and acceptances reference those mappings.
Imported history is distinguishable through this dedicated store; later local
events have no imported provenance row. Re-export emits effective origin metadata
for every imported entity, preserving provenance across later native migrations.

## Writer gate and lock order

Gate key: `736284910251`. Application transaction advice obtains the shared gate
at entry to every non-read-only transactional method in the application package,
before any row locks. Coverage includes registration, seeding, imported-author
creation, boards, questions/acceptances, replies, articles, reports and moderation.
Programmatic transfer transactions acquire it explicitly before transfer locks.
Read-only snapshot transactions do not acquire it.

Order: migration gate -> transfer control/job (when needed) -> identity rows in
UUID order -> board -> question -> reply -> report. Article-only paths branch after
identity. Activation inserts new mapped rows under exclusive ownership; it cannot
overlap ordinary domain writers. The gate drains earlier shared transactions and
rejects new application writers with **409 IMPORT_IN_PROGRESS** while exclusive.
It waits at most five seconds to acquire exclusivity; a busy deployment fails closed.

V16 also adds BEFORE STATEMENT triggers on all nine domain/identity/provenance
tables as a DML safety net. A conflicting direct SQL mutation raises SQLSTATE 55P03.
Triggers cannot reorder locks already taken by arbitrary operator SQL: manual
maintenance must coordinate deployment downtime or acquire this same gate before
any row locks. New programmatic write transactions must do the same. Do not acquire
an exclusive gate from a transaction that already holds a shared gate.

## Timeouts, recovery and retention

PostgreSQL transaction timeout is 30 seconds, each statement at most 25 seconds,
lock wait at most five seconds; the application also checks a monotonic 30-second
deadline between groups and while hashing. The 60-second worker lease outlives
the transaction. Timeout is failure with rollback, never permission to publish
visible batches. Larger imports need a separately proven atomic-visibility design.

After a lost response, the durable job/completion ledger is authoritative. Recovery
consults the matching completion fence. Without a marker an expired COMMITTING
attempt fails with ACTIVATION_RECONCILIATION_REQUIRED; it never replays insertion.
Ordinary worker exceptions produce a safe WORK_FAILED code and structured log with
job ID and safe exception locations, not payloads. A failed activation requires a new
import job, upload and review; existing confirmation cannot restart it.

Terminal staging/reviews/transient mappings and source files are removed by existing
reconciliation (normally every 15 minutes). Completion, confirmation metadata and
immutable provenance remain so cleanup does not break replay or attribution.

Storage enables scheduled activation (poll every five seconds). Set
`commonbeacon.transfer.import.activation.enabled=false` to disable confirmation and
the activation worker. Deployment-specific performance qualification is still
required before production use; `productionCapacityCertified` remains false.

## Verification

`ImportActivationIT` covers HTTP authority/CSRF/grants, exact acknowledgements,
stale/tampered input rejection, concurrent workers/writers, drain-before-recheck,
all entity-group rollback points including the completion boundary, timeout,
durable replay/recovery and semantic native re-export. `MilestoneUpgradeIT` verifies
forward migration from populated V15, preserving existing review metadata (old
validation-version-1 reviews must be run again before confirmation).

The maximum-record benchmark uses isolated PostgreSQL 18.6, the real ZIP inspector
and staging worker, 40,000 records across every native entity, and measures only
the final activation transaction. It prints `ACTIVATION_BENCHMARK` with elapsed
milliseconds, serialized staged bytes, WAL bytes and total domain/provenance index
bytes. Staging time is separate.

Local measured run on 2026-09-23: PostgreSQL 18.6 in Docker Desktop on Windows,
Java 21.0.12.1, 16 Docker CPUs and 19.15 GiB Docker memory; 40,000 source records, **16,621,800 staged bytes**, final transaction
**5,377 ms**, **49,838,560 WAL bytes**, **15,958,016 index bytes**. The source was a
STORED ZIP, so hashing covered the full uncompressed archive. The fixture includes
2,000 users, 100 boards, 5,000 questions, 20,000 replies, 5,000 acceptances, 1,000
articles, 2,000 contacts, 3,900 reports and 1,000 actions. Two bootstrap accounts
remain separate. A valid roughly 17 MiB article-heavy archive was refused during
review. Injected slow SQL hit the 25-second statement limit and rolled back.

These are representative local measurements, not a guarantee for every data
distribution or production hardware. Deadline enforcement remains authoritative.
Subsequent exports include retained bootstrap accounts and any new local data;
they enforce their own archive ceilings and may exceed them after a maximum-size
import. Increasing capacity or removing bootstrap identities is not implicit.
