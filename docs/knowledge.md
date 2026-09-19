# Milestone B knowledge and search contracts

Status: finalized in Stage 1 on 2026-09-19. Article APIs/UI are planned for
Stages 6–7, search for Stages 8–9; no endpoint below is added by this document.
The detailed search implementation notes will become `docs/search.md` in Stage 8.

## Shared conventions

Paths use `/api/v1`; UUID strings, UTC ISO-8601 timestamps, nonnegative versions,
plain-text rendering, sessions, CSRF, and ProblemDetail follow the existing app.
Use the same bounded page envelope and 32-bit offset limit described in
[moderation contracts](moderation.md). List defaults are page 0 and size 20;
maximum size 100. No client-controlled sort in this milestone. Unknown JSON
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
- ARCHIVED is terminal and read-only in Milestone B. No unarchive, return-to-draft,
  hard-delete, revision-history, scheduled publication, or slug-change endpoint.

Require `expectedVersion` on every existing-article write. Reject stale versions
with `409 STALE_EDIT` before evaluating transitions. Duplicate/repeated or forbidden
state transitions return `409 ARTICLE_STATE_CONFLICT`. Each successful transition
or edit increments version and updates `updatedAt`; a same-value edit may leave
version unchanged if no persisted fields change. No-op lifecycle actions conflict.

## Exact REST shapes: Stages 6–7

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

Add a new Flyway migration after existing and earlier Milestone B migrations;
verify the next unused version at implementation time. Store UUID ID, unique slug,
title, body, status check, author FK, created/updated timestamps, nullable
published timestamp, and `@Version`. Enforce that DRAFT has no published timestamp
and PUBLISHED has one; ARCHIVED permits either based on its history. Never modify
V1–V5. Add listing indexes based on actual queries.

Tests must cover administrator versus moderator/member/visitor permissions; CSRF;
forged metadata; trimmed validation and malformed/duplicate slugs; concurrent slug
creation; stale edit, publish, and archive races; draft/archived public `404` and
list totals; publication timestamps; immediate published edits; immutable slug
and preserved author; terminal archival; plain-text rendering and pagination.

UI routes are `/knowledge`, `/knowledge/:slug`, and `/admin/articles` (editor views
may be nested). Preserve content on validation/network/conflict errors and require
explicit reload/reconciliation. Invalidate public/admin detail/lists plus later
search/summary queries after state changes. Cancel and clear private queries on
logout, expiry, and account switch, including late response handling. Verify SPA
deep links, labels, focus, keyboard access, and narrow screens.

## Frozen search contract: Stages 8–9

`GET /search?q=...&page=0&size=20` is public. Missing q is equivalent to empty.
Trim q; maximum 200 UTF-16 code units. Reject excess length with
`400 VALIDATION_FAILED` and `fieldErrors.q`; blank/whitespace q returns an empty
page with zero totals. Validate page bounds even for blank input.

Each hit is exactly `{kind, id, title, snippet, url, rank}`. Kind is QUESTION or
ARTICLE; URLs are relative `/questions/{id}` or `/knowledge/{slug}`. Rank is a
finite nonnegative number, not a percentage or cross-query confidence score.
Snippet is at most 240 UTF-16 code units including an optional ellipsis, cut
without splitting surrogate pairs; it contains plain text with no trusted HTML.

Stage 8 searches titles using parameterized case-insensitive literal substring
matching with SQL pattern escaping, rank 0, and a bounded body-prefix snippet.
Stage 9 replaces matching with explicit PostgreSQL `english` full-text search over
weighted title (A) and body (B), using `websearch_to_tsquery`, `ts_rank_cd`, and
maintained tsvectors/GIN indexes. Input operators follow that parser; do not pass
raw query text as SQL. Stop-word-only or tokenless queries return empty results.
Keep the bounded plain-text snippet contract; highlights are not required.
Verify specific parser/index behavior against the installed PostgreSQL release
when implementing, rather than treating this plan as library documentation.

Both stages merge eligible questions and articles before global ordering and
pagination. Sort `rank DESC, kind ASC, id ASC`, with explicit kind order ARTICLE
before QUESTION, then PostgreSQL UUID ordering. Return the standard page envelope
with one combined count and identical visibility predicates for results/counts.
Use one read snapshot per response so items and totals correspond under concurrent
moderation/publication. Offset pages can still move across separate requests.

Eligible data: VISIBLE questions, including archived-board questions, and PUBLISHED
articles only. Replies, reports, moderator notes, draft and archived articles are
not sources. A hidden question contributes neither hit nor snippet nor count.
Hide/restore, editing, publish/archive must affect reads begun after their commit;
already-rendered text in another browser is not remotely erased. Refetch on
navigation/focus and invalidate current-client caches; no real-time push is claimed.

Search tests cover literal pattern characters in Stage 8; punctuation/operators,
empty and stop-word input in Stage 9; no matches, mixed kinds, global counts/pages,
rank ties, title/body weighting in controlled fixtures, stale results on edit,
distinctive private text, and migration backfill. Record query plans for selective
and broad queries using stated dataset sizes; do not promise an index scan on a
tiny table or an unmeasured speedup. English stemming, no typo correction, no
separate reply search, and page shifts are documented limits.

## Java discussion checkpoint

Explain DTO records, validation after normalization, author derivation from the
session, enum transition rules, transactional version checks, and public query
predicates. Compare JPA entity queries with typed native-SQL projections for mixed
search results. Rehearse publication and stale-edit paths in source when they exist;
these contracts are implementation targets, not evidence of personal proficiency.
