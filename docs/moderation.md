# Reporting and moderation

Member reporting, private review, resolution, restoration, audit history, and the
operational summary are implemented. See the [OpenAPI contract](openapi.json),
[operator walkthrough](operator-walkthrough.md), and
[verification evidence](evidence/milestone-b.md).

## Shared conventions and permissions

All paths use `/api/v1`. UUIDs are strings, timestamps are UTC ISO-8601 strings,
versions are nonnegative integers. Required numeric fields must distinguish missing
values from zero. Bodies are plain text. Trim text before validating it; reason,
resolution-note, and restoration-reason lengths are 5–2,000 Java/JavaScript UTF-16
code units. Null, blank, missing, malformed, and out-of-range required input fails.
Moderation request DTOs reject unknown fields with `400 INVALID_REQUEST`.
Legacy board/question/reply DTOs retain their existing input policies.

- Visitors read visible public content but cannot report or use moderation APIs.
- Members report visible questions/replies, including their own and content on
  archived boards. They cannot read reports, audit records, or hidden context.
- Moderators have member capabilities plus queue/context/history/summary reads,
  resolution, and restoration. Administrators have the same moderation rights.
- Only the question author selects/clears a solution. Moderation clears a hidden
  accepted reply as an invariant, not a general override of ownership.

All mutations require the current session and CSRF token. Protected reads require
authentication and the appropriate role. Check permissions in services as well as
HTTP routing. Existing CSRF filtering can return `403 CSRF_INVALID` before an
unauthenticated mutation reaches its authentication check; tests of `401` on
mutations must bootstrap a valid CSRF token first.

Privileged responses use `Cache-Control: no-store`. Public DTOs never contain
reports, reasons, resolution notes, hidden text, emails, or password data. A
privileged author/reporter/actor reference is only `{id, displayName}`.

Lists use `{items, page, size, totalElements, totalPages}`. Defaults: `page=0`,
`size=20`; size 1–100; page nonnegative; `page * size <= 2147483647` using wide
arithmetic. Beyond-last-page results are empty with accurate totals. No custom
sort parameter is supported; the API rejects unsupported filters/sorts rather than
interpolating them into SQL. Offset pages may shift during concurrent inserts.

## Report submission

`POST /reports` accepts exactly these fields:

```json
{"questionId":"<uuid>","replyId":null,"reason":"This contains private contact details."}
```

Exactly one target UUID must be non-null; the other may be omitted or null.
Derive reporter identity from the session. Validate target existence and public
visibility after obtaining coordinating locks. For replies, the parent must also
be visible. Archived boards remain reportable. Hidden or absent targets give
`404 CONTENT_NOT_FOUND`, with no disclosure of which condition occurred.

Return `201` with `{id, status: "OPEN", createdAt}` only. There is no member report
detail endpoint and no Location header advertising a privileged destination.
Duplicate open reports by the same reporter/target return `409 REPORT_ALREADY_OPEN`.
Other reporters can submit independently. Resolution frees that reporter/target
pair for a new report only when the target is publicly visible again.

V6 (`V6__create_content_report.sql`) creates `content_report` with
reporter and target foreign keys, exactly-one-target check, `OPEN`/`RESOLVED`
check, created/updated timestamps, version, nullable resolver, resolved timestamp,
resolution decision, and resolution note. Store the decision explicitly; a note
alone cannot distinguish dismissal from acknowledgement. OPEN rows have null
resolution metadata; RESOLVED rows have all resolution metadata. Add one partial
unique index per target kind for `(reporter_id, target_id) WHERE status = 'OPEN'`.
Reasons are retained; do not add report editing/deletion/reopening endpoints.

The member UI offers Report question and Report reply on visible content, including
archived boards. Expand the form, enter a reason, and submit. Success clears the
draft and displays a receipt notice. Duplicate reports display the server conflict;
validation/network failures preserve the typed reason. Reloading does not expose
existing private reports; the backend remains the authority on duplicates. Switching
accounts or targets discards private form state. Submission does not hide content,
change its version, clear accepted answers, or promise a completed moderator review.

Implementation lives in the backend `moderation` package and frontend
`features/moderation`. The service reuses board/question/reply locking repositories
and obtains the reporter through IdentityService. V6's text constraints count UTF-16
units (including supplementary characters) consistently with the request validator.
`ReportIT`, `ReportControl.test.tsx`, and `e2e/reports.spec.ts` verify submission.
Resolution and restoration have separate integration and browser coverage.

## Queue, report details, and restoration context

`GET /moderation/reports?status=OPEN&page=0&size=20` accepts `OPEN` or `RESOLVED`
(default OPEN). Order by `createdAt ASC, id ASC` for oldest-first triage.
Each `ReportSummary` contains `id`, `status`, `reason`, `reporter`, `targetKind`
(`QUESTION`/`REPLY`), `targetId`, `questionId`, `createdAt`, `updatedAt`, `version`,
`resolvedAt`, `resolver`, `resolutionDecision`, and `resolutionNote`. Resolution
fields are null while open. Text content is loaded through detail, not every row.

`GET /moderation/reports/{id}` returns `{report, context, availableDecisions}`.
`report` is the summary above. `availableDecisions` is an array of the enum values
below; it is a UI hint, always revalidated transactionally on submission.

The resolution form uses `availableDecisions`; independent content-history and
restoration pages are linked from report details. OPEN reports return DISMISS plus HIDE for a visible target, or DISMISS
plus ACKNOWLEDGE_HIDDEN for a target that is itself hidden; RESOLVED reports return
an empty array. An internally visible reply under a hidden question still has
HIDE eligibility, while effective public visibility is false.

Sign in as a moderator or administrator and use **Report review** in navigation,
or open `/moderation`. The status and page are URL parameters; changing status
resets to page zero. Select a report to open `/moderation/reports/{id}`; the Back
to reports link preserves the originating filter/page. Context includes board
archival, question/reply visibility, author names, retained acceptance, and resolved
metadata. Reasons and hidden content are rendered as plain text. Invalid filters,
loading, empty pages, missing reports, forbidden access, and retryable service
failures have explicit UI states. The queue remains available on archived boards.

Implementation uses `ModerationController`, `ModerationReadService`, and a dedicated
JDBC `ModerationReadRepository` returning explicit DTO records. Each service call
is read-only at REPEATABLE_READ isolation: the list/count and report/context queries
share one PostgreSQL snapshot. Queries use bound parameters and a fixed sort order;
only requested target context is loaded. No public query or visibility predicate
was relaxed, and no new migration was needed beyond Stage 2's V6 index.

The API and service both require moderator/administrator roles; success responses
are no-store. Frontend query keys include the actor ID, and request functions consume
abort signals. Existing session cancellation/cache clearing plus guarded, account-keyed
screens prevent loaded or late private responses from appearing after logout or
account switching. Server-side permissions remain authoritative.

`ModerationContext` has exactly:

- `board`: `{id, name, archived}`.
- `question`: `{id, title, body, author, visibility, version, acceptedReplyId}`.
- `reply`: null for a question target, otherwise
  `{id, body, author, visibility, version}` for the specific target reply.
- `targetKind`, `targetId`, and `effectivePublicVisibility` (boolean).

`acceptedReplyId` is internal privileged context and may be retained while the
question is hidden. Context loading must be consistent within a database snapshot,
so versions and displayed state correspond to the same review. Do not load an
unbounded thread or audit collection into a detail DTO.

The following protected content routes are available:

- `GET /moderation/questions/{id}` returns `ModerationContext` for any visibility.
- `GET /moderation/replies/{id}` returns context including the parent at any visibility.
- `GET /moderation/questions/{id}/actions?page=0&size=20` and the equivalent
  `/moderation/replies/{id}/actions` return that target's action history.

These routes let moderators reload hidden targets and restore a hidden parent
from a reply report. They are all moderator/administrator-only; there is no
public `includeHidden` switch. Unknown report IDs give `404 REPORT_NOT_FOUND`;
unknown privileged content IDs give `404 CONTENT_NOT_FOUND`.

Action pages order `createdAt DESC, id DESC`; items contain
`{id, actor, targetKind, targetId, action, reason, createdAt}`. `action` is `HIDE`
or `RESTORE`. Display report resolutions through report history, rather than
pretending dismissal or acknowledgement changed content visibility.

## Resolution and restoration: Stages 4–5

`POST /moderation/reports/{id}/resolve` accepts:

```json
{
  "decision":"HIDE",
  "resolutionNote":"Personal details must not remain public.",
  "expectedVersion":0,
  "expectedTargetVersion":2,
  "expectedQuestionVersion":4
}
```

`expectedVersion` refers to the report. `expectedTargetVersion` refers to the
question or reply being reviewed. `expectedQuestionVersion` is required for
every reply-target decision (including dismiss/acknowledge), and omitted or null
for question targets. This uniform reply rule deliberately conflicts when parent
context changes, even if a particular decision would not clear acceptance.
For question targets, their target version already represents the question.
Return `200` with the refreshed report-detail envelope.

- `DISMISS`: OPEN -> RESOLVED, no content change, allowed at either visibility.
- `HIDE`: OPEN -> RESOLVED and VISIBLE -> HIDDEN; write one audit action in the
  same transaction. The target's own visibility determines eligibility.
- `ACKNOWLEDGE_HIDDEN`: OPEN -> RESOLVED only when the target itself is HIDDEN;
  write no duplicate hide event. A visible reply under a hidden parent is not
  itself hidden and cannot use this outcome.

Resolve only the selected report; other reports remain open. A HIDE request after
another moderator hides the target must conflict and be explicitly re-reviewed,
not silently converted to acknowledgement. Re-resolving a resolved report gives
`409 REPORT_ALREADY_RESOLVED`; stale reviewed versions give `409 STALE_EDIT`.
Incompatible decisions give `409 MODERATION_STATE_CONFLICT`.

`POST /moderation/questions/{id}/restore` accepts
`{reason, expectedTargetVersion}`. The reply route additionally requires
`expectedQuestionVersion`. Return `200` with refreshed `ModerationContext`.
Only HIDDEN -> VISIBLE is allowed; already-visible restoration gives
`409 MODERATION_STATE_CONFLICT`. Append one RESTORE action atomically. Never reopen
a report or automatically reaccept a restored reply.

Check role, DTO validity, existence, report OPEN state, reviewed versions, then
decision/visibility preconditions in that order after taking locks. For restore,
omit the report-state check. All successful state changes advance the affected
entity versions; clearing acceptance advances the question version as well as
the hidden reply's version. Resolution advances report version. Do not increment
question version merely because an unrelated reply is hidden/restored.

Hide/restore safety actions remain allowed on archived boards. Hiding a question
preserves reply states and internal acceptance; public access to the whole thread
is suppressed. Separately hiding its accepted reply clears acceptance even when
the parent is hidden. Restoring a reply under a hidden parent changes its own
state only: `effectivePublicVisibility` remains false.

V7 added `moderation_action`: actor/target FKs,
exactly-one-target check, action enum check, required reason, creation timestamp,
and target/history indexes. Audit records are append-only by application behavior,
not a tamper-proof compliance log. No failed mutation may leave a misleading event.

## Transaction boundaries and lock order

Question edits locate scalar IDs, acquire the board lock, and load a fresh locked
question before checking visibility, ownership, and version. Reply creation and
editing use the same coordinating order.

For all moderation writes use board -> question -> target reply if any -> report
if resolving. Obtain locating IDs through scalar queries without preloading managed
entities; lock/reload authoritative state before decisions. Existing target-parent
relationships remain immutable. Report submission takes the same content locks
before inserting; uniqueness constraints remain the final protection. Article
operations use their own entity/version and never acquire thread/report locks.

Do not lock a report and then wait for its thread. Keep lock-holding transactions
short; no browser interaction or network calls inside them. Use consistent read
snapshots for compound privileged context and summary queries. Never rely on
cached entities or buttons for permission decisions.

## Errors and acceptance cases

Reuse `ProblemDetail` with `code`, `requestId`, optional `fieldErrors`, and no SQL
or stack traces. `400 VALIDATION_FAILED` covers field bounds; `INVALID_REQUEST`
covers malformed values, unknown fields, and invalid target shape;
`INVALID_PAGE`/`INVALID_STATUS` cover list input. Authentication/CSRF/forbidden
codes remain those from Milestone A. Translate known report uniqueness violations
to `REPORT_ALREADY_OPEN`, keeping generic `DATA_CONFLICT` as a safe fallback.

Required PostgreSQL/HTTP cases: exactly-one target and real FKs; concurrent duplicate
reports; independent reporters; self/archive reporting; hidden-parent exclusion;
role/CSRF rejection; two resolutions of one report; two reports hiding one target;
stale target/parent versions; hide accepted/nonaccepted replies; hide/restore parent
with mixed reply states; acknowledge-hidden distinctions; transaction rollback;
accept-versus-hide in both lock orders; edit/create/archive races. Assert stored
state, version changes, audit cardinality, and public reads, not only HTTP status.

Frontend cases: preserved reason on failure, no optimistic visibility success,
queue filters/pages, stale-context reload, hidden-parent explanation, keyboard and
narrow-screen usability, and account-switch/expiry cancellation of privileged
queries. Existing `AuthProvider` cancels and clears queries; new queries must pass
abort signals and must not let late privileged responses render for another user.

## Resolution screen and transaction

Moderators and administrators can resolve an open report from its detail screen.
Choose a decision and enter a private resolution note (5-2000 trimmed characters).
The form displays and submits one reviewed snapshot. Failed submissions preserve
its note and require **Reload report context**, review, and a new decision selection;
mutations are never automatically retried. Account changes remove private drafts,
and late responses cannot repopulate another account's cache.

`POST /api/v1/moderation/reports/{id}/resolve` implements the contract above.
The transaction locates scalar IDs, locks board -> question -> target reply ->
report, checks state and versions, and flushes the content and report before
reading the confirmed result. A standalone context read uses REPEATABLE_READ;
the resolution response joins its existing write transaction with content locks held.

V7 creates `moderation_action` with actor/target foreign keys, exactly one target,
HIDE/RESTORE action values, trimmed reason bounds, and target-history indexes.
HIDE and RESTORE are visibility actions. The application repository exposes an append
operation and no update/delete endpoint; this is not tamper-proof storage.
Dismissal and acknowledgement update resolution metadata without adding an action.
Other reports on the target stay open for explicit review and acknowledgement.

Hiding an accepted reply clears the selection and advances both reply and question
versions in the same transaction, including beneath a hidden question. Hiding an
unrelated reply leaves the question's version/selection unchanged. Hiding a question
preserves its internal selection and reply states, while public thread APIs exclude
it. Archived boards permit these safety actions without granting moderators general
permission to edit other authors' content or select their solutions.

Question editing now loads scalar board ID, locks the board, and then freshly locks
and checks the visible question. Reply creation/editing already follows this ordering.
Restoration, history endpoints, and both accept/hide lock orders are verified
by the restoration integration suite.


## Restoration screen and history

The six protected question/reply context, history, and restoration routes above
are implemented. Context and history reads use REPEATABLE_READ snapshots; history
pages default to 20, cap at 100, and use newest timestamp/UUID first. History
contains actor ID/display name, action, private reason, and timestamp, without
account credentials or report-resolution events. Unknown targets return
CONTENT_NOT_FOUND. Restoration DTOs reject extra fields and require the reviewed
parent version for reply targets; question requests omit it or supply null.

Restoration locks board -> question -> reply, checks the target's own visibility
and reviewed versions, sets only that target to VISIBLE, appends one RESTORE
entry, flushes, and returns confirmed context. Both action types use the V7 table.
It does not reopen reports or restore acceptance. A question restoration retains
a still-valid selection and does not alter hidden children. Reply restoration
under a hidden parent succeeds internally but remains publicly inaccessible.
Archival does not block moderation and still blocks ordinary author writes.

From any report detail choose **Question history and restoration** or **Reply
history and restoration**. The private routes are `/moderation/questions/:id`
and `/moderation/replies/:id`. These independent pages support parent restoration
without a public hidden-content bypass. Only a hidden target has a restoration
form. Enter a private reason and submit; failures retain the draft and require
**Reload content context** before another attempt. No mutation is automatically
retried or displayed optimistically. History has bounded previous/next controls,
loading/error/empty states, and account-scoped queries with abort signals.

Deterministic PostgreSQL tests hold the first operation's real board lock,
observe the second operation waiting in pg_stat_activity, and then release it.
They cover both lock orders for accept versus reply/parent hide, author edit or
reply creation versus hide, restoration versus another hide, and board archival
versus hide/restore. Two reports targeting one reply produce one hide followed
by an explicit acknowledgement after reload. Injected audit failure proves
restoration rollback; Stage 4 retains the accepted-reply hide rollback test.

## Operational overview

Moderators and administrators see three cards above the report queue at
`/moderation`. The protected `/api/v1/moderation/summary` endpoint returns only
`unansweredQuestions`, `openReports`, and `publishedArticles`. No query parameters
are accepted; unsupported filters return `400 INVALID_REQUEST`. A fresh seeded
database returns `{"unansweredQuestions":0,"openReports":1,"publishedArticles":1}`.

- Unanswered questions are VISIBLE questions without a VISIBLE accepted reply
  belonging to the same question. An unselected reply does not make a question
  solved. Archived boards remain included; hidden questions are excluded.
- Open reports count OPEN rows, including separate reports about the same target.
- Published articles count PUBLISHED rows, excluding drafts and archived articles.

Hiding an accepted reply clears acceptance and increases the unanswered count
for its visible parent. Restoring that reply alone does not reaccept it. Hiding
a question excludes it; restoring a question with a retained valid selection
keeps it solved. These rules reuse the existing moderation transactions.

The service uses one aggregate SQL statement with a NOT EXISTS predicate, rather
than loading entities or issuing three separate counts. Under PostgreSQL's
[Read Committed isolation](https://www.postgresql.org/docs/18/transaction-iso.html),
the statement sees one committed snapshot. Counts may change immediately afterward;
they are not a live feed or a promise about a later list request.

Cards have loading, explicit zero, denied, and retryable failure states. Errors
hide previously cached counts. Links open the existing boards, open-report queue,
and public knowledge library; the board/library links are not new count-filtered
lists. Account-specific cache keys, cancellable requests, session cache clearing,
and `no-store` responses protect private counts across logout/account changes.
Successful question creation, solution changes, reporting, moderation, and article
mutations invalidate overview data. Returning to the page or browser focus refreshes
it; changes in another session are not pushed automatically.

The rollback-only [measurement script](../scripts/measure-operational-summary.sql)
uses 10,000 questions, 10,000 replies, 6,000 reports, and 3,000 articles. The
[captured PostgreSQL plan](evidence/summary-stage10-plan.txt) returned exact counts
6,000/2,000/1,000 in 5.826 ms, using a hash anti-join and sequential scans. These
temporary, reduced-column fixtures are diagnostic rather than a production latency
benchmark. No new index or schema migration is justified by this measurement;
exact counts still require work proportional to eligible data as the dataset grows.
