# Quarantined archive upload

Upload and inspection APIs require ACTIVE administrators; the [import screen](import-ui.md)
provides the browser workflow. Inspection never inserts users, content, moderation records
or staging mappings, and never permits activation. Target eligibility, staging and
dry-run review are implemented separately in [Stage 10](import-dry-run.md); activation belongs to Stage 11.

## Request flow

Use same-origin authenticated cookies and CSRF headers. Confirm the current password
at `/api/v1/account/data/reauthentication` with scope `IMPORT_UPLOAD`. Then:

1. POST `/api/v1/admin/data/imports` with a UUID `Idempotency-Key` and JSON
   `{formatVersion:1,recentAuthGrant:token,provider:"NATIVE"}`. Provider is optional
   and defaults to NATIVE; DISCOURSE selects the [versioned Discourse bundle](discourse-import.md).
   Other fields are rejected. Provider participates in request idempotency and
   cannot change after creation. The 201 result is an owner-scoped UPLOADING job.
   Matching retries reuse it.
2. PUT `/api/v1/admin/data/imports/{id}/archive` with raw `application/zip` bytes
   for NATIVE or `application/json` for DISCOURSE, matching the selected provider,
   and CSRF. No multipart, source URL, path or client filename is accepted. A fully
   received, durably stored archive receives a SHA-256 and 200 UPLOADED response.
   Partial uploads never become inspection input. On interruption, restart the whole
   upload for that UPLOADING job; a cancelled/expired job needs a new initiation.
   An upload already in progress, a completed upload, or a busy worker returns 409.
3. Poll the existing company job endpoint. The shared deployment-wide lease prevents
   upload/inspection and export work from overlapping. The inspector claims UPLOADED,
   validates in VALIDATING and leaves REVIEW_REQUIRED regardless of validity.
4. GET `/api/v1/admin/data/imports/{id}/inspection` for archive SHA-256, validity,
   rows checked, error count, inspection timestamp and up to 1,000 file/line/code
   issues. Pending inspection returns 409. `activationAvailable` is always false.
   No raw record values, content samples, filenames supplied by an attacker or private
   storage paths are included. Activation requires the separate reviewed workflow.

Other administrators cannot upload into, inspect, cancel or download your jobs.
Each read/write/worker phase rechecks ownership and current account authority;
role/account revision changes revoke the job. Existing job cancellation applies.
Quarantine artifacts are never served by the export download endpoint. Responses
are no-store. Upload completion and inspection have durable audit events; worker
logs carry job IDs and safe diagnostics, never source content.

## Limits and supported ZIP subset

The Discourse provider has a separate 8 MiB actual-upload limit and strict JSON
bundle reader (duplicate keys, UTF-8, depth, fields, version, IDs and source counts).
It creates bounded native records and runs the same schema/relationship validator.
The ZIP subset below applies to NATIVE only. Both providers use the same private
storage, ownership, recent-authentication, worker fencing and cleanup machinery.

The upload reader and route-specific Nginx allowance both cap actual archive bytes
at 64 MiB. The ordinary API retains its 1 MiB proxy cap. Request buffering is disabled
on the archive route so Nginx does not spool private archives to its temporary paths.
Servlet multipart is disabled; raw ZIP streaming bypasses multipart/form limits.
A 30-second connector/proxy idle timeout and ten-minute application deadline bound
uploads (an already blocked read can take up to the idle timeout to unwind). Inspection
has a ten-minute deadline with cancellable reads and database checkpoints. Inputs
expire one hour after initiation; cleanup retries every 15 minutes. Inspection does
not extend retention. Private storage quotas and free-space checks still apply.

The strict native-v1 reader supports stored and raw-DEFLATE regular file entries,
with optional standard data descriptors and UTF-8 filename flags. It rejects ZIP64,
split/encrypted archives, comments, extra fields, self-extracting prefixes, trailing
bytes, unknown methods, directory/symlink entries and header/descriptor disagreements.
This intentionally supports a narrower subset than arbitrary third-party ZIP tools.
Use archives emitted by CommonBeacon's company exporter.

There are at most ten exact ASCII allowlisted members: manifest.json plus the defined
JSONL entities. Path variants, case variants, duplicates, nested archives and unknown
members are rejected. No member is extracted to disk, rendered, executed or fetched.
Random access reads only bounded ZIP metadata; entry content is decompressed through
bounded buffers into the existing JSONL/schema validator. A 100:1 compression ceiling,
128 MiB per JSONL entry, 256 MiB total JSONL, 64 KiB manifest, 256 KiB lines, nesting
limit 8, entity limits and 40,000 total rows apply. Actual inflated bytes, CRC, file
SHA-256, counts and archive digest are checked; ZIP header claims are insufficient.
Malformed UTF-8, JSON, duplicate keys and personal portability profiles are rejected.
The codec also checks archive-internal references; this is not a target-state dry run.

All quarantine bytes use generated private store keys. Interrupted writes are deleted
or left tracked for retryable cleanup. A server crash leaves an expired lease and
fenced attempt; inspection can restart at most three times. Cancellation/revocation
is checked between bounded chunks, at checkpoints and before publication. No schema
or content supplied inside an archive is used as executable code or validator logic.

## Operations and verification

Enable the existing private storage overlay. Inspection runs by default when storage
is enabled; `commonbeacon.transfer.import.inspection.enabled=false` pauses its worker.
This is separate from the export worker flag. Incomplete uploads do not get claimed.
V14 adds inspection metadata and audit event values; existing domain rows are untouched.

Tests include native fixtures and real HTTP uploads, ownership/CSRF/grant checks,
interruption, storage exhaustion, streaming byte limits, corruption, private-profile
rejection, cancellation, revocation, expiry and hostile ZIP structures. A disposable
Nginx test checks the 64 MiB upload route and unchanged ordinary API limit. Capacity
and parser bounds are enforced limits, not a measured production throughput claim.

Implementation references: [ZIP format specification](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT)
and [Tomcat HTTP connector timeout semantics](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html).
