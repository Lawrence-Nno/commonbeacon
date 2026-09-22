# Data transfer permissions and downloads

The API supports password confirmation, requester-scoped job lists/status,
cancellation and protected delivery of existing artifacts. Export creation,
import upload, snapshot generation and live import activation are not available
yet. The reusable frontend password prompt is ready for future transfer screens;
there is no transfer screen in the application navigation yet.

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
defines the exact DTOs and errors. Creation endpoints remain unavailable.

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
