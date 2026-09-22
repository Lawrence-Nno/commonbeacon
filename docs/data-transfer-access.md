# Data transfer permissions and downloads

The API supports company export creation and generation, password confirmation,
requester-scoped job lists/status, cancellation and protected downloads. Personal
export creation, import upload and live import activation are not available yet.
Administrators can use **Data management** in the application navigation to create
company exports, check their own history, cancel supported jobs, and download archives.
See the [administrator guide](data-management.md).

## Current identity and permission checks

Company jobs require the current database role ADMINISTRATOR. Personal export jobs
belong only to the signed-in requester. Another administrator cannot inspect,
cancel or download a requester's job. Missing IDs, another requester's IDs and IDs
in the wrong namespace return 404 after the namespace's role check; insufficient
company permissions return 403. Unauthenticated requests return 401.

V11 adds an authorization revision to each account. A database trigger advances it
when role, password hash or email changes, including demotion followed by promotion.
Workers pin this revision when a job is created and recheck it at claim, heartbeat,
checkpoint and publication. A revoked job fails instead of retrying under its old
authorization. Transfer endpoints read the database rather than trusting cached
session roles. V12 adds account-state changes to revision tracking and requires
ACTIVE accounts for requests and workers. Existing sessions for unavailable or
inactive users are invalidated on their next request.

Short authorization transactions hold a shared account-row lock; a concurrent role
update waits for that check to finish. File I/O occurs outside those transactions.
Revocation stops subsequent protected checkpoints or publication; it cannot undo
bytes already read or sent. Live import transaction/activation rules are not yet
implemented.

## Password confirmation

POST `/api/v1/account/data/reauthentication` with the session cookie, CSRF header
and `{password, scope}`. Scopes are COMPANY_EXPORT, PERSONAL_EXPORT, IMPORT_UPLOAD,
IMPORT_COMMIT and DOWNLOAD. Company/import scopes require current administrator
status. The response is `{token, expiresAt}`: a single-use, five-minute grant bound
to actor, authorization revision, session ID and scope.

Only token hashes are retained in the server-side session, with at most eight live
grants and eight tickets. Logout, session expiry/rotation and backend restart
invalidate access. Password/email/role changes invalidate existing grants and
tickets. Password confirmation is not MFA. Attempts are limited to five per actor
and twenty per socket address per fifteen minutes; 429 includes Retry-After: 900.
Proxy deployments currently share a socket-address allowance.

The frontend prompt clears the password on submission, cancellation and session
expiry. Account/scope changes reset it; late responses after cancellation or account
changes are discarded. Passwords and grants are not saved in browser storage or
query caches. Small JSON POST bodies on these routes are capped at 4096 bytes.

## Job API

Use `/api/v1/admin/data/jobs` for company jobs and `/api/v1/account/data/jobs` for
personal exports. Both support:

- GET the collection, with size 1-100 (default 20) and optional opaque cursor.
  Results sort newest first and return `{items, nextCursor}`.
- GET `/{id}` for a summary without storage keys, worker identities or credentials.
- POST `/{id}/cancel` with `{expectedVersion}`, CSRF and UUID Idempotency-Key.
  Matching retries replay for 24 hours; changed input conflicts. Cancellation does
  not need a recent-authentication grant.
- POST `/{id}/download-ticket` with `{recentAuthGrant}` and CSRF. This consumes a
  DOWNLOAD grant and issues `{token, expiresAt}`, valid for 60 seconds and one use.
- GET `/{id}/download` with X-Download-Ticket and the same session cookie. Never
  place a ticket in a URL. Responses use application/zip and a generated attachment
  filename. Disabled/unavailable storage returns 503.

All these responses use Cache-Control: no-store. The [OpenAPI contract](openapi.json)
defines the exact DTOs and errors. Company export creation is described below.

## Download revocation and cleanup

A database lease permits one active download per job for at most ten minutes.
Cleanup waits for that lease to finish or expire. New downloads still reject an
expired artifact. The handler checks artifact length/checksum, current identity,
authorization revision, session validity and lease before sending each 64 KiB
chunk. On revocation before response commitment it returns a safe error. After
commitment it stops the stream without appending JSON to archive bytes; clients
must reject incomplete downloads. Bytes already delivered cannot be recalled.

Audit events distinguish an attempt from a completed server delivery. Completion
means the server finished writing the verified bytes, not that the recipient saved
them successfully. Tickets are consumed even if delivery subsequently fails; obtain
a new grant and ticket for a retry. See [private storage and recovery](data-transfer-storage.md).

## Verification

`TransferAccessIT` exercises real HTTP sessions/CSRF, ownership, stale roles,
role changes during work and delivery, grant/ticket replay and expiry, throttling,
bounded requests, pagination, cancellation retries and cleanup leases against
disposable PostgreSQL and private temporary files. Frontend tests cover password
clearing and late-response isolation. These checks do not establish a complete
export/import workflow or production load capacity.

## Imported author identities

V12 adds ACTIVE and IMPORTED_INACTIVE account states. Existing accounts remain
ACTIVE with unchanged credentials and roles. Imported authors receive generated
local UUIDs, their historical names and creation timestamps, MEMBER roles, and
null login emails/passwords. PostgreSQL constraints reject credentials or elevated
roles on inactive authors. Login, current-user authorization, existing HTTP sessions,
transfer requests and worker checkpoints reject unavailable/inactive accounts.
State changes advance the authorization revision used by grants and jobs.

The internal `ImportedAuthors` primitive requires an enclosing transaction; the
future activation worker must call it only after validated review and fenced
activation authorization. It is not exposed as an HTTP endpoint. The durable
`imported_author` table maps source-instance/source-user UUIDs uniquely to local
authors. Duplicate mappings fail and roll back creation, rather than rewriting or
matching an existing account. Updates to provenance are rejected by the database.
Mappings survive transfer-job/artifact expiry.

Source emails are retained only with the contact option, in this private table.
They are never login identifiers or ownership evidence. Multiple imported authors
may have the same contact email, including an existing administrator's address.
Registration at that address creates an independent account when otherwise available;
it grants no ownership of historical content. Public attribution remains local ID
and display name; source contacts and provenance are excluded from public DTOs.
No imported role or verification claim is accepted by the creation primitive.

Account claiming, invitations, and password recovery are unavailable. The database
also rejects activation of imported accounts. A later email/identity implementation
must explicitly replace that guard, verify ownership through a locally authorized
flow, review the historical content ownership being transferred, record an audit
trail, and revoke affected sessions/grants. Source email or a reset request alone
must never perform that handoff. No automatic email/display-name merge is supported.

`ImportedIdentityIT` covers duplicate/forged contacts, private contact exclusion, readable
attribution, immutable mappings, rollback, denied role/credential/activation changes,
stale sessions and worker revocation. `MilestoneUpgradeIT` covers populated V11
upgrades as well as earlier baselines. Full archive activation remains future work.

## Company export

Enable private storage using the [Compose overlay](data-transfer-storage.md#private-local-store)
or equivalent durable storage configuration. The scheduled worker polls every five
seconds and shares the deployment-wide lease with other transfer workers. The
administrator screen at `/admin/data` provides the following API workflow.

1. Sign in as an ACTIVE administrator and obtain CSRF as for other mutations.
2. POST `/api/v1/account/data/reauthentication` with password and scope
   `COMPANY_EXPORT`. Treat the returned token as a secret.
3. POST `/api/v1/admin/data/exports` with a UUID `Idempotency-Key`, CSRF header,
   and `{includeContacts:false, includeModerationHistory:false,
   acknowledgedPrivateContent:true, recentAuthGrant:"<token>"}`. The response is
   `202` with the standard job summary. Hidden content and unpublished articles
   are always included. Set either optional flag to true only when deliberately
   requesting that private section; omitted/null flags mean false independently.
4. Poll `/api/v1/admin/data/jobs/{id}` until READY or a terminal failure. Matching
   idempotent retries return the original job, even with its already-consumed grant;
   changed options return 409. Replay still requires current administrator access.
5. Obtain a fresh DOWNLOAD grant, exchange it for a job-specific ticket, then
   download using the header-based route above. Repeated downloads need fresh
   tickets and do not delete the artifact. Downloads expire after 24 hours.

V13 persists the selected options and a stable, non-secret source-instance UUID.
A read-only REPEATABLE_READ transaction extracts explicit field projections in
keyset pages of at most 32 rows into one private intermediate file. It includes
all users, boards, questions, replies, acceptances, and article states. Optional
contacts include known active-account addresses and retained imported contacts;
users without a known contact have no contact row. Imported author origins survive
export. Moderation history remains untrusted historical data on import, regardless
of the source actor's current role.

The snapshot has a 120-second deadline and byte/row limits. Source rows are never
trimmed or repaired. After closing the transaction, the worker validates all entry
counts, digests and relationships, then packages the ZIP. Invalid source fields or
relationships fail with INVALID_SOURCE_DATA; size/count limits use
TRANSFER_LIMIT_EXCEEDED; snapshot timeouts use SNAPSHOT_TIMEOUT. Other storage or
execution failures use WORK_FAILED. No raw source text is returned in these errors.
Exclusions and private-content warnings are declared in the manifest.

ZIP output caps at 64 MiB; total JSONL at 256 MiB, each entry at 128 MiB. Entries
that would compress beyond 100:1 use ZIP STORED instead, and still count against the
64 MiB output cap. Worker attempts have a ten-minute overall budget. Extraction,
validation, and compression use bounded buffers; the codec's relationship index is
row-bounded and still needs the planned capacity/heap measurements.

A recovered lease discards all previous intermediate/output bytes and starts a new
snapshot from the first page. Complete snapshots are not reused across attempts in
this implementation. No database snapshot remains open during packaging or download.
Publication rechecks the current requester and fence, then atomically marks a fully
written ZIP READY. Cancellation/revocation/failure leaves no downloadable partial;
cleanup retries failed physical deletions. Existing protected-download checks handle
client disconnects, expiry, revocation and corrupt files.

`CompanyExportIT` checks the fixture projections, independent private options,
concurrent edits/hides/publication, recovery, invalid source relationships, limits,
low disk, compression ratio fallback, HTTP authorization, replay, downloads and
expiry. The disposable persistence suite creates an export through the scheduled
worker and checks identical protected bytes after service restarts and Compose
down/up, including cleanup of its separate artifact volume.
