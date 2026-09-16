# REST API quick reference

Use the same origin as the UI, normally http://127.0.0.1:8081. IDs are UUIDs.
Content is plain text. Responses never include password hashes or email addresses.

## Session and CSRF

1. GET /api/v1/auth/csrf. Retain the session cookie. Response:
   `{"headerName":"X-CSRF-TOKEN","token":"<token>"}`.
2. POST /api/v1/auth/register with JSON
   `{"email":"member@example.test","displayName":"Example Member","password":"<12–128 characters>"}`.
   Include X-CSRF-TOKEN. Returns 201, without automatic login.
3. Fetch a fresh CSRF token, then POST /api/v1/auth/login as
   application/x-www-form-urlencoded: email=...&password=...
4. Login returns `{"id":"<UUID>","displayName":"Example Member","role":"MEMBER"}`.
   GET /api/v1/auth/me returns the same shape, or 401 when signed out.
5. POST /api/v1/auth/logout with a fresh CSRF token returns 204.

Send cookies and a fresh CSRF token on every mutation, including login and
registration. A browser sends same-origin cookies automatically. Do not
automatically retry writes after a lost response.

## Boards

- GET /api/v1/boards returns an array, including archived boards.
- GET /api/v1/boards/{id} returns a board.
- POST /api/v1/boards requires an administrator:
  `{"slug":"setup-help","name":"Setup help","description":"Share setup questions."}`.
  Returns 201 and Location.
- PATCH /api/v1/boards/{id} requires an administrator. Example:
  `{"archived":true,"expectedVersion":0}`.
  Optional editable fields: slug, name, description, archived. Returns 200.

Board response fields: id, slug, name, description, archived, createdAt, version.

## Questions and replies

- GET /api/v1/boards/{id}/questions?page=0&size=20&status=all
  accepts all, solved, or unanswered. Unanswered means no visible accepted solution,
  even if replies exist. Questions sort newest first, with ID tie-break.
- POST /api/v1/boards/{id}/questions requires a member session:
  `{"title":"How do I get started?","body":"I have read the setup guide. What should I try next?"}`.
  Returns 201 and Location.
- GET /api/v1/questions/{id} returns detail.
- PATCH /api/v1/questions/{id} requires its author:
  `{"title":"Updated question title","body":"Updated details with enough context.","expectedVersion":0}`.
- GET /api/v1/questions/{id}/replies?page=0&size=20 returns oldest first, with ID tie-break.
- POST /api/v1/questions/{id}/replies:
  `{"body":"Try the documented setup steps."}`. Returns 201 and Location.
- GET /api/v1/replies/{id} returns a visible reply.
- PATCH /api/v1/replies/{id} requires its author:
  `{"body":"Updated helpful steps.","expectedVersion":0}`.

All list pages use:
`{"items":[],"page":0,"size":20,"totalElements":0,"totalPages":0}`.
Page is nonnegative, size is 1–100, and the offset must fit a 32-bit integer.
Question titles are trimmed, 5–200 characters; bodies 10–20,000.
Reply bodies are trimmed, 1–20,000 characters.

Question detail fields: id, board (id/name/archived), title, body, author
(id/displayName), createdAt, updatedAt, version, solved, acceptedReply.
Reply fields: id, questionId, body, author (id/displayName), createdAt, updatedAt,
version. Question summaries omit body and acceptedReply and include boardId/solved.

## Accepted solutions

PUT /api/v1/questions/{id}/accepted-reply:
`{"replyId":"<UUID>","expectedVersion":0}`.

DELETE /api/v1/questions/{id}/accepted-reply?expectedVersion=1.

Both require the question author and return updated question detail, including
the new question version. Authors may accept their own reply. Administrators and
moderators cannot bypass ownership. Reply membership, visibility, archival state,
and version are checked in the transaction.

## Errors and restrictions

Responses use application/problem+json with status/detail/code/requestId, and
fieldErrors where applicable. Use status and fieldErrors for controlled UI feedback;
retain drafts on recoverable errors.

- 400: invalid fields, IDs, pagination, or status.
- 401: session required.
- 403: missing CSRF, wrong role, or wrong owner.
- 404: missing/hidden content or a reply outside the target question.
- 409: stale version, archived-board write, or conflicting unique value.
- 429: login throttling, with Retry-After.

Archived conversations remain readable. Hidden parents/replies are excluded from
public endpoints. Hidden accepted content is suppressed defensively on reads;
future moderation must atomically clear acceptance when hiding.
