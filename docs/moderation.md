# Milestone B moderation contract

Status: contract finalized in Stage 1 on 2026-09-19; endpoints and schema below
are planned for Stages 2–5 and 10, not implemented by this document.

## Shared conventions and permissions

All paths use `/api/v1`. UUIDs are strings, timestamps are UTC ISO-8601 strings,
versions are nonnegative integers. Required numeric fields must distinguish missing
values from zero. Bodies are plain text. Trim text before validating it; reason,
resolution-note, and restoration-reason lengths are 5–2,000 Java/JavaScript UTF-16
code units. Null, blank, missing, malformed, and out-of-range required input fails.
New request DTOs reject unknown fields with `400 INVALID_REQUEST`; scope this
policy to new DTOs rather than changing existing Milestone A contracts globally.

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
sort parameter in this milestone; reject unsupported filters/sorts rather than
interpolating them into SQL. Offset pages may shift during concurrent inserts.

## Report submission: Stage 2

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

The next available migration is V6 at Stage 1. Implement `content_report` with
reporter and target foreign keys, exactly-one-target check, `OPEN`/`RESOLVED`
check, created/updated timestamps, version, nullable resolver, resolved timestamp,
resolution decision, and resolution note. Store the decision explicitly; a note
alone cannot distinguish dismissal from acknowledgement. OPEN rows have null
resolution metadata; RESOLVED rows have all resolution metadata. Add one partial
unique index per target kind for `(reporter_id, target_id) WHERE status = 'OPEN'`.
Reasons are retained; do not add report editing/deletion/reopening endpoints.

## Queue, report details, and restoration context: Stages 3 and 5

`GET /moderation/reports?status=OPEN&page=0&size=20` accepts `OPEN` or `RESOLVED`
(default OPEN). Order by `createdAt ASC, id ASC` for oldest-first triage.
Each `ReportSummary` contains `id`, `status`, `reason`, `reporter`, `targetKind`
(`QUESTION`/`REPLY`), `targetId`, `questionId`, `createdAt`, `updatedAt`, `version`,
`resolvedAt`, `resolver`, `resolutionDecision`, and `resolutionNote`. Resolution
fields are null while open. Text content is loaded through detail, not every row.

`GET /moderation/reports/{id}` returns `{report, context, availableDecisions}`.
`report` is the summary above. `availableDecisions` is an array of the enum values
below; it is a UI hint, always revalidated transactionally on submission.

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

Add these explicit extensions to the guide in Stage 5:

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

Use a later additive migration for `moderation_action`: actor/target FKs,
exactly-one-target check, action enum check, required reason, creation timestamp,
and target/history indexes. Audit records are append-only by application behavior,
not a tamper-proof compliance log. No failed mutation may leave a misleading event.

## Transaction inspection and required lock order

Source inspection at `2d33c96` found:

- `BoardService.update` locks the board, then validates its version and mutates.
- `QuestionService.create` locks the board before checking archival and inserting.
- `QuestionService.accept` uses a scalar visible-board lookup, then locks board,
  question, and selected reply; version/owner/archive checks follow the locks.
- `QuestionService.update` loads a managed visible question and checks ownership
  before locking its board. It does not reload/recheck question visibility after
  the wait. Existing optimistic versioning protects against concurrent stale
  persistence, but this path does not yet follow the stronger moderation design.
- `ReplyService.writableQuestion` gets a scalar board ID, locks board then visible
  question, and checks archive state. Creation uses it; editing first resolves a
  scalar parent ID, then uses it, loads the reply and relies on its UPDATE/version
  check for the final reply write. No managed reply is loaded before the board lock.

Before enabling hiding in Stage 4, change question editing to scalar lookup ->
board lock -> fresh locked question -> visibility/ownership/version checks.
Recheck all reply paths as moderation is added. Existing visible-only lock queries
cannot restore hidden rows; add separate privileged lock queries rather than
loosening public predicates. This is future implementation work, not a fix made
in Stage 1, and the inspection alone is not evidence of a current failed invariant.

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

## Operational summary: Stage 10

`GET /moderation/summary` returns exactly
`{unansweredQuestions, openReports, publishedArticles}` as nonnegative integer
counts, computed in one database snapshot. It is moderator/administrator-only.
Count visible questions with no effective visible accepted reply across all boards,
including archived ones. Count OPEN report rows (not distinct targets), and only
PUBLISHED articles. A question with replies but no accepted solution is unanswered.
The response contains no target text, draft counts, or report identities.

## Java discussion checkpoint

Trace authentication -> controller/DTO validation -> authorized transactional
service -> ordered repository locks -> state transition -> flush/commit -> DTO.
Explain why a lock protects concurrent execution, a supplied version protects
reviewed intent, and a constraint protects stored structure. A preloaded managed
entity can remain stale after waiting for a lock; a fresh locked read avoids making
decisions from that state. Rehearse this with source; this document does not certify
personal fluency.
