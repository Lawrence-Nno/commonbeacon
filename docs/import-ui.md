# Import and reconciliation

Administrators open **Data management → Import company data** at
`/admin/data/imports`. `/admin/data/imports/history` lists only their import
requests, 20 per page. Export history remains separate. A request has a stable
`/admin/data/imports/{jobId}` URL for refreshing or returning later.

## Upload and review

Choose a native company v1 ZIP or a [Discourse 3.5.0 bundle](discourse-import.md)
and confirm your password. The selected provider is bound to the request and cannot
change during retries. The page reports bytes
sent and then waits for server verification. A complete upload enters private
inspection; run the dry run after inspection passes. Personal archives cannot
be activated. Archives and filenames are kept only in browser memory, never
local/session storage. After navigation, reselect the whole file only if the
request is still waiting for upload. Interrupted uploads restart in full;
server-side staging can resume independently.

The review shows archive/review hashes, source format and product versions,
source scope and exclusions, validation errors, identity collisions, entity
counts, limits, and up to 100 source/local ID mappings. Errors are paged 20 at a
time, with at most 1,000 retained. Downloadable JSON reports contain bounded
metadata and error codes, not raw content or contact values. Archive-declared
exclusions and warnings are rendered as text.

Activation requires an empty target containing only active bootstrap
administrators, with demo seeding disabled. The seeded development community
will fail this check. See [activation](import-activation.md) for the 40,000-record,
16 MiB staged JSON and 30-second transaction envelope and deployment measurements.
The native ZIP upload ceiling is separately 64 MiB; Discourse JSON is limited to 8 MiB.

Imported authors remain inactive and cannot sign in or acquire permissions.
Contacts and private moderation history follow the source export options;
changing scope requires a new export. Hidden content and drafts retain their
visibility. Credentials, sessions, grants, permissions and attachments are not
restored. Search vectors are rebuilt and edit versions start at zero.

## Confirmation and uncertain outcomes

Acknowledge private content, inactive authors and each review warning, then
confirm your password again. The request uses the exact acknowledged review
digest, target generation and job version. Polling pauses during the password
prompt. A stale/conflicting response clears acknowledgements and returns to
review. The browser never fetches a replacement review and submits it silently.
Older reports without source scope require a new dry run.

Cancellation closes when confirmation is accepted and the state becomes
`COMMITTING`. This is not success: only `COMPLETED` means the dataset committed.
If a response is lost, use **Check outcome** or reopen the request URL. There is
no automatic activation retry. During the exclusive write transaction, status
may temporarily be unavailable; subsequent polls reconcile the durable outcome.
Failure leaves all imported domain records uncommitted.

## Reconciliation and privacy

`GET /api/v1/admin/data/imports/{id}/reconciliation` requires the current owning
administrator and returns `Cache-Control: no-store`. It is available for terminal
imports only. An opaque cursor pages durable mappings, 100 at a time. It never
exposes another requester's import or raw archive payloads.

Expected counts mean reviewed/staged records. Completed imports report all of
them created, none skipped/rejected. These historical counts come from the
durable activation receipt and survive staging cleanup or subsequent live edits.
Cancellation counts reviewed records as skipped; failure counts them as
rejected/uncommitted, with zero created. A request that never produced a review,
or whose temporary review has expired, shows unavailable counts rather than
invented zeros. Confirmed activation receipts remain available. A missing receipt
for a completed job is an error, not a zero-record success.

The reconciliation JSON download includes the current mapping page and cursor;
it is explicitly not a download of all mapping pages. Review JSON includes its
bounded errors and preview. Logging out, switching accounts, losing permission
or leaving the screen aborts outstanding work and clears files, password forms,
previews, query caches and download URLs. Late responses cannot restore them.

## Verification

`npm --prefix frontend run test:smoke` creates two isolated Docker projects:
source A with disposable demo data and target B with demo seeding disabled and
one bootstrap administrator. It exports through the UI, retries an interrupted
upload, rejects a changed target review, loses the accepted confirmation response,
checks status, and verifies reconciliation, content and visibility on B. Both
databases use tmpfs; the runner never touches development volumes. The existing
community/export suite runs first, then the disposable source is recreated before
the import suite. This keeps one suite's reservations and password-confirmation
rate limits from affecting the other without weakening production limits. Reports
and logs are retained separately under `frontend/test-results/community` and
`frontend/test-results/imports`. Unit and
integration tests additionally cover account changes, late results, permission
checks, retained receipts and cancellation/failure counts.
