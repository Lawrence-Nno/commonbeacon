# Public search: Stage 8 title baseline

Stage 8 adds `GET /api/v1/search` and the `/search` browser route. It searches
case-insensitive literal substrings in titles of visible questions and published
knowledge articles. This is an intermediate baseline: weighted English full-text
search and its indexes remain Stage 9. Body text is used only for snippets, never
for matching at this stage. There is no relevance ranking, typo correction, reply
search, highlighting, or real-time push to other browsers.

## API contract

`GET /api/v1/search?q=setup&page=0&size=20` is available without signing in and
returns the same public results for every role. Parameters are optional; defaults
are empty q, page 0, size 20. Unsupported or repeated parameters return
`400 INVALID_REQUEST`. Malformed integer parameters, negative pages, sizes outside
1-100, or offsets above 2,147,483,647 return `400 INVALID_PAGE` even when q is blank.

Trim q using Java String.trim. Blank/whitespace queries return no hits and zero
totals. More than 200 UTF-16 units after trimming returns `400 VALIDATION_FAILED`
with `fieldErrors.q`. The UI also checks length before requesting results.

```json
{
  "items": [{
    "kind": "ARTICLE",
    "id": "00000000-0000-0000-0000-000000000008",
    "title": "Setup guide",
    "snippet": "Follow these fictional setup instructions.",
    "url": "/knowledge/setup-guide",
    "rank": 0.0
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Each hit is exactly `{kind,id,title,snippet,url,rank}`. Kind is ARTICLE or QUESTION;
question URLs are `/questions/{id}`, article URLs `/knowledge/{slug}`. Rank is
always zero for this baseline. Snippets contain at most 240 UTF-16 units, including
a final ellipsis when truncated. A boundary never splits a surrogate pair. The
database projects at most 241 body code points, then Java applies the UTF-16 bound.
Titles and snippets are plain text; the UI renders neither as HTML.

## Query and visibility design

SearchRepository uses bound JDBC parameters with `ILIKE ... ESCAPE '!'`. Escape
`!` first, then `%` and `_`, before adding the enclosing substring wildcards.
Quotes, backslashes, and other punctuation are literal bound data. These semantics
follow [PostgreSQL 18 pattern matching](https://www.postgresql.org/docs/18/functions-matching.html).

One shared SQL expression selects PUBLISHED articles and VISIBLE questions and
combines them with UNION ALL. Count and item queries use that same expression.
The combined result is ordered by rank descending, explicit ARTICLE-before-QUESTION
kind order, then PostgreSQL UUID ascending order. LIMIT/OFFSET apply to the combined
set, as described in [PostgreSQL's combined-query rules](https://www.postgresql.org/docs/18/queries-union.html).
The same UUID in two content kinds remains two distinct hits.

SearchService wraps count and items in a read-only REPEATABLE_READ transaction.
Archival or hiding that commits between the two statements cannot produce a
mixed snapshot. The next search sees the committed change. Separate offset-page
requests may shift when content changes; snapshot consistency is per response.

Hidden questions and draft/archived articles contribute no hit, snippet, or total.
Visible questions on archived boards remain searchable. Replies, report reasons,
resolution notes, moderation history, users, and article drafts are not search
sources. Privacy predicates run before pagination, never as client-side filtering.
No schema migration or index is introduced in Stage 8.

## Browser behavior and cache updates

Search appears in the shared navigation from both community and knowledge pages.
`/search?q=...&page=...` supports direct loading, refresh, and back/forward history.
Submitting a new query resets page to zero; submitting the same query refreshes
the first page. Invalid pages have first-page recovery. Blank, loading, no-match,
unavailable, and validation states are explicit; retry does not clear the query.

Query keys contain normalized q and page. Requests consume AbortSignal; a late
response from an obsolete query cannot replace the current query's results.
Results refetch on navigation/focus, and errors hide stale result text. Successful
question creation/editing, report resolution, content restoration, and article
mutations invalidate the search prefix. The shared authentication provider also
clears query caches when accounts change. Another browser's already-rendered
content remains until it searches or refreshes; no remote erasure is claimed.

The form has an explicit label, keyboard submission, focus styling, and a wrapping
layout. The decoder rejects malformed hits, excessive snippets, nonfinite/negative
ranks, unknown kinds, and external or mismatched result URLs.

## Verification and query plans

SearchIT tests the real HTTP API/PostgreSQL behavior: merged pages and UUID ties,
role parity and private exclusion, archived-board eligibility, literal pattern
characters and quotes, blank/invalid bounds, UTF-16 snippets, committed edits and
visibility changes, and concurrent archival between count and items. Component
tests cover text rendering, URLs/history, page reset, cancellation/late responses,
error/retry states, validation, and safe response decoding. The browser journey
uses real API fixtures and checks mixed pagination, private exclusion, deep links,
literal punctuation, keyboard/mobile use, and publication/moderation refresh.
See [milestone evidence](evidence/milestone-b.md) for actual runs and counts.

`scripts/measure-title-search.sql` creates temporary tables shadowing the two
source names within its psql session. It creates 2,000 article and 2,000 question
fixtures, runs ANALYZE, and records EXPLAIN (ANALYZE, BUFFERS) for selective/broad
count and first-page queries, then rolls back. Application rows and tables are
untouched. These temporary fixtures intentionally have no indexes; results are a
small measured baseline, not a production performance guarantee or an index
comparison. Stage 9 needs separate measurements of its actual weighted query and
GIN indexes on stated datasets.

Measured on local PostgreSQL 18.6 on 2026-09-20, the selective query matched two
rows: count execution 2.334 ms and hits 2.403 ms. The broad query matched 3,098
rows: count 2.770 ms and first 20 hits 6.834 ms. Both scanned the temporary sources
sequentially. The selective result used a 26 kB quicksort; the broad first page
used a 42 kB top-N heapsort. Each query touched 668 local buffers. These are single
warm local observations on index-free fixtures, not latency targets or evidence
of an indexed production speedup. Full plans were recorded at
`%TEMP%/commonbeacon-b-stage8-query-plans.log`.

Run the rollback-only measurement against the local Compose database:

```powershell
Get-Content -Raw scripts/measure-title-search.sql |
  docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1'
```

## Java discussion checkpoint

Trace URL input through bounded validation, JDBC parameter binding, typed record
projection, the shared visibility predicate, snapshot transaction, and React query
key. Explain why independent source pagination or removing private rows after
paging breaks counts and page boundaries. Constant rank is a contract placeholder,
not a relevance score. Personal explanation practice is separate from test results.
