# Accepted solutions

Only the question author can select, replace, or clear a solution. Owning the
reply or having a moderator/administrator role does not grant this permission.
Authors may accept their own replies. Archived boards reject acceptance changes,
including clearing; hidden questions and hidden or unrelated replies return 404.

## API

- `PUT /api/v1/questions/{id}/accepted-reply` with
  `{"replyId":"<reply UUID>","expectedVersion":0}` selects or replaces a solution.
- `DELETE /api/v1/questions/{id}/accepted-reply?expectedVersion=1` clears it.
- Both require session authentication and CSRF, and return the updated question
  detail with `200`. The version is the **question** version, not the reply version.
- Question detail now includes `solved` and `acceptedReply` (a public reply DTO,
  or null). Question summaries include `solved`.
- `GET /api/v1/boards/{id}/questions?status=all|solved|unanswered&page=0&size=20`
  filters before counting and paginating. The default is `all`. Here
  **unanswered means no visible accepted solution**, even if the question has replies.
  Invalid filters return 400. Existing pagination bounds and stable ordering apply.

Invalid payloads return 400, unauthenticated requests 401, forbidden actors or
missing CSRF 403, unavailable content 404, and stale versions/archived boards 409.
Selection changes also update the question timestamp and version; concurrent
question edits therefore conflict with acceptance changes rather than overwrite them.

## Database and Java concepts

Flyway V5 adds nullable `question.accepted_reply_id`, a unique constraint on
`reply(question_id,id)`, and a composite foreign key from
`question(id,accepted_reply_id)` to that pair. A direct SQL write cannot select
a reply belonging to another question. The single nullable reference permits at
most one selected reply. There is no separate persisted solved flag.

Public solved status additionally requires the referenced reply to be visible. If a reply is hidden without clearing its reference (for example by a direct database write), question details suppress its body and author, and board badges, counts, and filters treat the question as unanswered. Reads do not mutate the stored reference. Moderation clears an accepted reference atomically when hiding a reply. List queries fetch accepted replies alongside authors to avoid per-question visibility lookups.

These protections solve different problems:

- **Authorization** checks that the authenticated actor owns the question.
- **Database constraints** protect membership and referential integrity even
  when application code is bypassed. They do not enforce ownership or visibility.
- **Locks** serialize mutations while the transaction checks current state.
  Acceptance follows the established **board -> question -> reply** order.
  The board lock coordinates archival; the question lock precedes any reply
  lock. A scalar board lookup avoids loading an outdated managed question before
  locking it. Reply hiding follows this same order and clears an
  accepted selection atomically. PostgreSQL integration tests cover both accept-versus-hide lock orders.
- **Expected versions** reject a stale user's intent after waiting for a lock.
  JPA `@Version` also protects against other versioned question writers.

DTO conversion happens inside the transaction. Detail reads fetch the accepted
reply and its author; serialization never exposes JPA entities, emails, or hashes.
Locks currently serialize thread activity across a board, a deliberate simple
approach inherited from archival handling; higher-throughput locking is future work.

## Frontend

SolutionPanel and SolutionButton render the controls; useSolution owns pending/error state, selection requests, explicit reload, and cache updates. Replies retains reply pagination, composition, and editing.

An accepted-answer panel stays visible regardless of the current reply page.
The question author sees select/replace/clear controls while the board is open.
Changes appear only after the server confirms success. Submission disables the
controls; errors offer an explicit status reload before another attempt, avoiding
automatic retries of a mutation whose outcome may be uncertain. Successful changes
update question detail and invalidate board-list caches. The board filter lives
in the URL, resets to page zero when changed, and persists between pages.

## Verification

See [verification](verification.md) for repeatable commands, coverage, recorded
results, and current limitations.
