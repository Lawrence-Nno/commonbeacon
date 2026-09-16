# Stage 6: questions and owner editing

## Use the feature

Open a board and choose **Ask a question** after signing in. Enter a title and
details, then publish. The detail page shows the author's display name and saved
timestamps. Reloading or opening its link in another browser reads the stored
question. Question text is plain text; line breaks are preserved and HTML is
displayed literally.

The author sees **Edit question** on an open board. Other members, moderators,
and administrators cannot edit someone else's question. Archive status is
enforced on the server, including when a board closes while a request is waiting.

Board pages show 20 questions at a time with Previous/Next links. The page number
is in the URL, so refreshing and using browser history preserve navigation.
Archived boards and their visible questions remain readable.

Errors preserve title/body input. A stale edit returns 409 and offers
**Reload latest and discard draft**. Copy anything you want to keep before
choosing that action. The edit form retains the version of its original draft;
background requests do not silently upgrade that version.

## REST contract

All routes have the /api/v1 prefix. Public responses contain safe author
summaries (id and displayName), never email, password hash, or persistence entities.

- GET /boards/{boardId}/questions?page=0&size=20: public visible-question list.
  Returns items, page, size, totalElements, and totalPages. Summaries include
  id, boardId, title, author, createdAt, updatedAt, and version; body is omitted.
- GET /questions/{id}: public detail. Includes id, board {id,name,archived},
  title, body, author, createdAt, updatedAt, and version.
- POST /boards/{boardId}/questions: authenticated JSON {title,body}.
  Returns 201, Location, and question detail.
- PATCH /questions/{id}: authenticated owner only; JSON
  {title,body,expectedVersion}. Both text fields and the nonnegative version
  are required. Returns updated detail.

Titles are trimmed and must have 5–200 characters; trimmed bodies must have
10–20,000 characters. Creation assigns author, board, visibility, timestamps, and
version on the server. Extra input fields cannot change author, visibility,
board membership, or version. PATCH cannot move a question between boards.

Mutations use the existing session cookie and fresh CSRF header. Anonymous writes
with a valid token return 401; missing CSRF returns 403. Unauthorized owners return
403; hidden or missing questions return 404 even to their owner/administrator.
Invalid fields, IDs, and pagination return controlled 400 responses. Closed boards
and stale edits return 409 with BOARD_ARCHIVED or STALE_EDIT.

Pages start at zero; size defaults to 20 and must be 1–100. Offset must fit a
32-bit integer. Sorting is createdAt DESC, id DESC, with the ID resolving equal
timestamps. Offset pagination can shift page boundaries when new questions are
inserted between requests. A page beyond the current end returns an empty items
array with the real totals.

## Persistence, transactions, and Java learning

V3 creates question with board/author foreign keys, visibility checks, trimmed
text bounds, timestamps, and a version column. The board/createdAt/id index
supports the listing order. V1 and V2 remain unchanged. Accepted replies arrive
in Stage 8 after the reply table exists.

The JPA entity represents stored data. Request records validate input; response
records define safe API fields. Mapping happens inside service transactions with
Open Session in View disabled. Entity graphs fetch authors for lists and the
board/author for detail, so serialization does not trigger lazy database reads.

The service derives the author from Authentication. IdentityService.requireOwner
compares IDs without a privileged-role bypass. Spring method security also
requires authentication at the service entry point.

expectedVersion detects forms already out of date. Hibernate @Version includes
the stored version in the UPDATE condition, catching edits that race after the
initial check. Every successful edit updates updatedAt and increments version.
Question timestamps use microseconds so the immediate response matches the
PostgreSQL value read after reload.

Question creation/editing and board metadata/archive changes acquire a database
write lock on the board until commit. That makes the archive check and write one
ordered operation: a request waiting behind archival rechecks the closed board.
This simple policy serializes writes within a board and is appropriate for the
current learning application. Future reply/moderation operations must retain a
consistent locking order; question/reply row locks, when added, must still acquire
the question before its replies.

Hidden-question filtering is implemented in public queries and counts now.
There is no user-facing hide/restore feature yet. Later moderation writes must
increment version so concurrent edits cannot overwrite visibility changes.

## Verification

Run scripts/verify.ps1 from the repository root. Backend tests use disposable
PostgreSQL databases and real cookie sessions. Question tests cover:

- Session-derived authors, trimming, safe responses, persistence across requests.
- Owner-only edits, including moderator/administrator denial.
- Archived-board reads and rejected writes, including a transaction race.
- Hidden-question exclusion from details, lists, counts, and owner edits.
- Field bounds, malformed/missing records, CSRF, and anonymous requests.
- Default/bounded pagination, deterministic tie ordering, and empty pages.
- Simultaneous edits yielding one success and one conflict.
- Board/author foreign keys and visibility constraints.

Frontend tests cover literal text rendering, owner controls, draft preservation,
explicit stale reload using the new version, field errors, pagination, malformed
responses, and archived/missing states.

The Chrome smoke suite requires the running backend, local demo seeding, Chrome,
and DEMO_PASSWORD loaded with scripts/use-local-database.ps1. In frontend run
npm run test:smoke. It uses separate owner, other-member, administrator, and
anonymous contexts, including a two-tab stale edit and desktop/mobile screenshots.
It leaves unique fictional boards/questions/accounts in the development database;
it does not delete application data. Full isolated browser fixtures belong to
Stage 10. Replies and acceptance remain future stages.

## Verified results — 2026-09-16

scripts/verify.ps1 passed: 5 backend unit tests and 34 integration tests, with no
failures/errors/skips; 36 frontend tests; lint; TypeScript checking; production build.
All 6 Chrome smoke tests passed. Desktop and mobile question screenshots were
inspected, and the mobile overflow check passed. Changes are uncommitted and
remote CI has not run for Stage 6.
