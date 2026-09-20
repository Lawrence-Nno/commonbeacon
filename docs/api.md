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
moderation atomically clears acceptance when hiding an accepted reply.


## Knowledge articles (Stage 6 API)

Public reads expose PUBLISHED articles only:

- GET `/api/v1/articles?page=0&size=20`: published-time descending, then ID descending.
- GET `/api/v1/articles/{slug}`: detail or the same 404 for unknown/draft/archived slugs.

Only administrators can use these no-store routes:

- GET `/api/v1/admin/articles?page=0&size=20&status=DRAFT`: omit status for all;
  otherwise use DRAFT, PUBLISHED, or ARCHIVED. Updated-time descending, then ID.
- GET `/api/v1/admin/articles/{id}`: detail at any status.
- POST `/api/v1/admin/articles` with
  `{"slug":"first-steps","title":"Your first steps","body":"Follow these fictional setup instructions."}`.
  Returns 201, Location, DRAFT, and version 0.
- PATCH `/api/v1/admin/articles/{id}` with
  `{"title":"Updated first steps","body":"Updated instructions for the article.","expectedVersion":0}`.
  Both text fields are required. Published edits become public immediately.
- POST `/api/v1/admin/articles/{id}/publish` or `/archive` with `{"expectedVersion":0}`.
  Use the version actually reviewed, not the example value.

Slugs are lowercase, globally unique, and immutable. Original authorship is
preserved. Drafts can publish or archive; published articles can edit or archive;
archived articles are terminal/read-only. Text is trimmed and bounded. Unknown
JSON fields fail with 400. Stale versions return 409 STALE_EDIT; slug duplicates
return ARTICLE_SLUG_CONFLICT; invalid/repeated transitions return
ARTICLE_STATE_CONFLICT. Page defaults are 0/20, maximum size 100; unknown list
parameters and invalid status/page values fail with 400.

See [the knowledge contract](knowledge.md) for exact public/admin DTOs, limits,
publication timestamp behavior, and a complete session-based walkthrough.
Article screens are available at `/knowledge`, `/knowledge/:slug`, and
`/admin/articles`; see the [article UI walkthrough](knowledge.md#stage-7-screens-and-browser-walkthrough).

## Public full-text search (Stage 9)

GET `/api/v1/search?q=setup&page=0&size=20` requires no session. It combines visible
questions and published articles in one globally ranked page; archived-board
questions remain eligible. Weighted English vectors cover titles (A) and bodies
(B). Replies and private moderation data are not sources.

`websearch_to_tsquery` accepts words, quoted phrases, OR, and minus exclusions.
English stemming applies; punctuation no longer follows Stage 8 literal substring
semantics. Missing/blank/tokenless/stop-word-only queries return no results. Trimmed
q is bounded to 200 UTF-16 units; excess length returns 400 VALIDATION_FAILED with
fieldErrors.q. Query text is parameter-bound, never interpolated as SQL.

Hits contain `{kind,id,title,snippet,url,rank}`. Snippets are plain text, at most 240
UTF-16 units. Rank is a nonnegative ts_rank_cd score; order is descending rank,
ARTICLE before QUESTION, then UUID ascending. Page defaults remain 0/20, maximum
size 100 and offset 2,147,483,647. Invalid pages return 400 INVALID_PAGE even for
blank searches. Unknown/repeated parameters return 400 INVALID_REQUEST. Count and
items share a snapshot and visibility predicates.

Use `/search?q=setup&page=0` in the browser. See [search documentation](search.md)
for parser examples, V9 backfill/index design, limitations, tests, and query plans.
