# Knowledge articles

Administrators manage article drafts, publication, live edits, and archival.
Public readers browse published guidance at `/knowledge` and `/knowledge/:slug`.
See [OpenAPI](openapi.json), [search](search.md), and
[verification evidence](verification.md).

## Shared conventions

Paths use `/api/v1`; UUID strings, UTC ISO-8601 timestamps, nonnegative versions,
plain-text rendering, sessions, CSRF, and ProblemDetail follow the existing app.
Use the same bounded page envelope and 32-bit offset limit described in
[moderation contracts](moderation.md). List defaults are page 0 and size 20;
maximum size 100. No client-controlled sort is supported. Unknown JSON
request fields fail with `400 INVALID_REQUEST` on these new DTOs only.

Article title length is 5–200 and body 10–20,000 after `String.trim()`, measured
as Java/JavaScript UTF-16 code units. Slugs are trimmed, 3–100 characters, matching
`[a-z0-9]+(?:-[a-z0-9]+)*`; reject uppercase/invalid characters instead of silently
rewriting URLs. Slugs are globally unique across every status and immutable.

## Permissions and lifecycle

Visitors, members, moderators, and administrators can read published articles.
Only administrators list/read drafts or archived records and create, edit, publish,
or archive articles. Moderator capabilities do not include article administration.
Derive original author from the administrator creating the article and preserve it
on later edits. Do not accept `authorId`, `status`, or timestamps from clients.

- Creation always produces DRAFT, version 0, null `publishedAt`.
- DRAFT allows title/body edits, publication, and archival.
- Publish moves DRAFT -> PUBLISHED and sets `publishedAt` once.
- PUBLISHED allows title/body edits and archival. Edits are public immediately on
  commit; there is no separate working revision. The editor must explain this.
- Archive moves DRAFT or PUBLISHED -> ARCHIVED. Preserve any prior publication
  timestamp; an archived never-published draft retains null `publishedAt`.
- ARCHIVED is terminal and read-only. No unarchive, return-to-draft,
  hard-delete, revision-history, scheduled publication, or slug-change endpoint.

Require `expectedVersion` on every existing-article write. Reject stale versions
with `409 STALE_EDIT` before evaluating transitions. Duplicate/repeated or forbidden
state transitions return `409 ARTICLE_STATE_CONFLICT`. Each successful transition
or edit updates `updatedAt` and advances the persisted version when the entity
changes. No-op lifecycle actions conflict.

## REST shapes

Public DTOs never contain drafts, administrative state, another user's email, or
password data. `PublicAuthor` is `{id, displayName}`.

- `ArticleSummary`: `{id, slug, title, author, publishedAt, updatedAt}`.
- `ArticleDetail`: summary fields plus `body`.
- `AdminArticleSummary`: `{id, slug, title, status, author, createdAt, updatedAt,
  publishedAt, version}`. `publishedAt` is nullable.
- `AdminArticleDetail`: administrator summary fields plus `body`.

`GET /articles?page=0&size=20` returns a page of ArticleSummary restricted to
PUBLISHED, ordered `publishedAt DESC, id DESC`. `GET /articles/{slug}` returns
ArticleDetail for PUBLISHED only. Unknown, draft, and archived slugs all return
`404 ARTICLE_NOT_FOUND`; neither list totals nor errors disclose private existence.

`GET /admin/articles?page=0&size=20&status=DRAFT` returns a page of
AdminArticleSummary. Omitted status means all; supplied status must be DRAFT,
PUBLISHED, or ARCHIVED. Order `updatedAt DESC, id DESC`.
`GET /admin/articles/{id}` returns AdminArticleDetail at any status or `404`.
All administrator responses have `Cache-Control: no-store`.

`POST /admin/articles` accepts `{slug, title, body}`; returns `201`, the complete
AdminArticleDetail, and `Location: /api/v1/admin/articles/{id}`.
`PATCH /admin/articles/{id}` accepts `{title, body, expectedVersion}`; both text
fields are required (a complete editor save, matching existing question editing),
and returns `200` AdminArticleDetail. Slug edits are rejected as unknown input.

`POST /admin/articles/{id}/publish` and `/archive` accept `{expectedVersion}` and
return `200` AdminArticleDetail. Do not add arbitrary status changes to PATCH.
Role checks apply both in HTTP security and application services; anonymous writes
may first fail CSRF if they omit a valid token.

Errors: existing `UNAUTHENTICATED`, `FORBIDDEN`, `CSRF_INVALID`, `VALIDATION_FAILED`,
`INVALID_REQUEST`, `INVALID_PAGE`, and `STALE_EDIT`; add `400 INVALID_STATUS`,
`404 ARTICLE_NOT_FOUND`, `409 ARTICLE_SLUG_CONFLICT`, and
`409 ARTICLE_STATE_CONFLICT`. Known slug uniqueness races map to the dedicated
conflict; generic database details remain private.

## Persistence and acceptance cases

V8 adds knowledge_article after the V7 moderation-action migration. It stores
UUID ID, unique slug, title, body, status check, author FK, created/updated timestamps, nullable
published timestamp, and `@Version`. Enforce that DRAFT has no published timestamp
and PUBLISHED has one; ARCHIVED permits either based on its history. V1-V7 are
unchanged. Listing indexes cover public published-time order,
administrator update-time order, and status-filtered update-time order.

Integration tests cover administrator versus moderator/member/visitor permissions; CSRF;
forged metadata; trimmed validation and malformed/duplicate slugs; concurrent slug
creation; stale edit, publish, and archive races; draft/archived public `404` and
list totals; publication timestamps; immediate published edits; immutable slug
and preserved author; terminal archival; plain-text rendering and pagination.

UI routes are `/knowledge`, `/knowledge/:slug`, and `/admin/articles` (editor views
may be nested). Preserve content on validation/network/conflict errors and require
explicit reload/reconciliation. Invalidate public/admin detail/lists plus
search/summary queries after state changes. Cancel and clear private queries on
logout, expiry, and account switch, including late response handling. Verify SPA
deep links, labels, focus, keyboard access, and narrow screens.

## Search integration

Only PUBLISHED articles participate in [public full-text search](search.md).
Draft and archived text contributes no hits, snippets, or totals. Published edits
update generated vectors in the same database write. Client mutations invalidate
search and operational-summary queries; other sessions refetch on navigation/focus.

## Transaction behavior and API walkthrough

The article entity implements DRAFT/PUBLISHED/ARCHIVED transitions with microsecond
UTC timestamps and optimistic versions. Administrator mutations acquire a fresh
pessimistic article-row lock, compare expectedVersion before checking state, and
flush before returning the updated DTO/version. These transactions do not acquire
board, question, reply, or report locks. Database uniqueness is the final slug-race
protection; only its known constraint maps to ARTICLE_SLUG_CONFLICT.

Original author and slug have no mutation path and are non-updatable JPA columns.
Creation resolves the author from the authenticated administrator. A later admin's
edit preserves that original author. Archival is terminal; no delete or arbitrary
status PATCH endpoint exists. A repeated publish/archive conflicts rather than
silently succeeding. This implementation updates updatedAt on each successful edit.

List queries select summary columns without body and join author display names in
the same query. Both count/items run in one REPEATABLE_READ snapshot. Public detail
loads an explicit author projection from a PUBLISHED-only entity query; public DTOs
omit status, version, and createdAt. Administrator responses are no-store.
Offset pages may still shift between separate requests.

Using the session and fresh CSRF flow in [the API reference](api.md):

1. POST `/api/v1/admin/articles` with
   `{"slug":"first-steps","title":"Your first steps","body":"Follow these sample setup instructions."}`.
   Expect 201 with Location, DRAFT, version 0, and null publishedAt.
2. GET `/api/v1/articles/first-steps` returns 404 while it is a draft.
3. POST `/api/v1/admin/articles/{id}/publish` with `{"expectedVersion":0}`.
   Expect PUBLISHED, version 1, and a publication timestamp.
4. GET `/api/v1/articles/first-steps` now returns the public article.
5. PATCH `/api/v1/admin/articles/{id}` with
   `{"title":"Updated first steps","body":"These updated instructions are public immediately.","expectedVersion":1}`.
   Expect version 2 with the original slug, author, and publishedAt preserved.
6. POST `/api/v1/admin/articles/{id}/archive` with `{"expectedVersion":2}`.
   Expect ARCHIVED/version 3; public detail returns 404 and public totals exclude it.

Replace example versions with the version actually reviewed. A lost response or
409 requires a reload before deciding whether to submit again. JSON bodies are
plain text, including literal markup; the article screens render them as text.

## Screens and browser walkthrough

Public navigation includes Knowledge. `/knowledge?page=0` lists published guides
in pages of 20; `/knowledge/:slug` shows title, original author, publication time,
last update, and plain-text body. Empty, invalid-page, loading, unavailable, and
not-found states are explicit. Public queries refresh on navigation/focus and
discard displayed content on failed reads, including 404 after archival. Other
browsers already showing content do not receive real-time removal notifications.

Administrators have Manage articles navigation. `/admin/articles` supports all,
DRAFT, PUBLISHED, and ARCHIVED filters with URL-backed pagination. Create article
opens `/admin/articles/new`; saved records open `/admin/articles/:articleId`.
Visitors, members, and moderators cannot load private editor queries. Backend
authorization remains authoritative.

1. Sign in as an administrator and choose Manage articles, then Create article.
   Enter a unique lowercase hyphenated slug, title, and plain-text body. Create
   draft saves a private record and makes the slug read-only.
2. Edit and save the draft. Publish article is enabled only after edits are saved.
   Open its public URL in a separate visitor session to verify publication.
3. The published editor warns that saving changes updates the public article
   immediately. There is no unpublished working revision of a published article.
4. Open the editor in two tabs. Save one, then submit the other to see the stale
   version conflict. The second tab preserves its text and blocks further writes.
   Load latest article displays the current server title, body, and status beside
   the form. Choose Use server copy or Keep my draft after review, then explicitly
   submit again. An archived server copy cannot be reconciled into another write.
5. Archive article removes public availability and makes the editor read-only.
   Archival is terminal. Public draft/archived URLs return the same not-found view.

Validation failures preserve form content for correction. Network, server, and
conflict failures on existing records require explicit reload/reconciliation;
mutations never retry automatically. Failed creation keeps its fields for a
manual retry; the globally unique slug prevents duplicate creation if the first
request committed but its response was lost.

Successful mutations invalidate administrator lists/details, public article
queries, and search/moderation query prefixes.
Private queries include the actor ID and consume cancellation signals. Logout,
expiry, and account changes use the shared authentication cache cancellation and
clear operation; actor-keyed editors unmount, clearing draft state. Late mutation
responses cannot update caches, form state, or navigation after unmount. Drafts
are held in memory only; leaving or reloading the editor discards unsaved changes.

Forms use labelled inputs, native keyboard controls, visible focus, wrapped action
buttons, and a responsive textarea. The browser suite exercises the lifecycle,
two-tab reconciliation, role boundaries, SPA deep links, keyboard submission,
mobile layout, and logout/account switching against the real API.
