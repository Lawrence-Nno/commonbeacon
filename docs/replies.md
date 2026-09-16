# Replies (Stage 7)

## Behavior

Question pages now display public replies oldest first, with reply ID breaking
timestamp ties. Pages default to 20 items; the API accepts sizes from 1 to 100.
Signed-in members can post replies and edit only their own. Question ownership,
moderator status, and administrator status do not grant reply edit permission.

Bodies are trimmed and must contain 1-20,000 characters. The server derives the
author from the session; clients cannot move a reply to another question or
change its author. Text is rendered literally, without interpreting HTML.

Archived boards retain readable conversations but reject reply creation and edits.
Hidden replies and replies under hidden questions return 404 from public detail
and mutation endpoints and are excluded from public lists and counts.

## REST API

- GET /api/v1/questions/{questionId}/replies?page=0&size=20: public paginated replies.
- GET /api/v1/replies/{id}: public detail, also used for explicit conflict recovery.
- POST /api/v1/questions/{questionId}/replies: authenticated, CSRF-protected creation.
  JSON: {"body":"A helpful answer"}. Returns 201 and a Location header.
- PATCH /api/v1/replies/{id}: authenticated owner edit with CSRF.
  JSON: {"body":"An updated answer","expectedVersion":0}.

Responses contain id, questionId, body, author (id/displayName only), createdAt,
updatedAt, and version. They never serialize JPA entities or account credentials.
Validation returns 400, unauthenticated mutations 401, forbidden edits or missing
CSRF 403, unavailable records 404, and stale edits/archived boards 409.

## Java and database learning notes

Flyway V4 creates reply with question and author foreign keys, body/visibility/version
checks, and an index on question_id, created_at, id. Foreign keys prevent dangling
references even when writes bypass application code.

Reply uses lazy ManyToOne relationships. Repository entity graphs fetch authors
for list/detail responses; conversion to the ReplySummary record happens inside
the service transaction. JSON serialization receives records, not lazy proxies.

The service checks expectedVersion and JPA @Version prevents lost updates.
Writes acquire locks in board -> question -> reply-update order. Board archival
uses the same board lock, so a reply write waiting behind archival rechecks the
archived state after obtaining the lock. Scalar parent lookup avoids loading an
outdated managed question before acquiring that lock.

Keep this order when Stage 8 adds accepted solutions and later moderation adds
visibility writes. Accepted replies and moderation controls are not implemented here.

## Frontend behavior

The question placeholder is replaced with the reply list, composer, pagination,
and author edit controls. A successful mutation refreshes replies, question detail,
and the board-question cache. Posting navigates to the last reply page.

Save buttons disable while pending. Validation and network failures retain drafts.
A stale edit offers "Reload latest and discard draft"; the edit version changes
only after reloading both the reply and its parent successfully. Account/thread
changes discard private drafts. Archived drafts remain visible with saving disabled.

## Verification (2026-09-16)

scripts/verify.ps1 passed: 5 backend unit tests, 43 PostgreSQL/HTTP integration
tests, 41 frontend tests, lint, type checking, and production build.

Nine ReplyIT tests cover second-member replies, forged author rejection, owner-only
editing, hidden parents/replies, archive restrictions, validation, CSRF, pagination
and tie ordering, concurrent edits, writes waiting behind archival, and the
question foreign key. Frontend tests exercise draft preservation, pending saves,
explicit conflict reload, archived drafts, and malformed responses.

All six Chrome smoke tests passed. The question smoke test now also creates a
second-member reply, reloads it, rejects a forged edit, edits from two tabs,
recovers a conflict, verifies public literal-text rendering, and checks archived
reply controls. The 390px mobile screenshot was inspected and overflow checks pass.

Run Chrome checks with the backend running and local demo configuration loaded:
scripts/use-dev-tools.ps1, scripts/use-local-database.ps1, then in frontend run
npm run test:smoke. See boards.md for opt-in demo accounts. Smoke tests leave
fictional development records; isolated browser fixtures belong to Stage 10.

Stage 6 was committed and pushed as 19ce858. Stage 7 is locally verified and
uncommitted; remote CI has not run for these changes.
