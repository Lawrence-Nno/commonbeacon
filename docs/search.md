# Public search

`GET /api/v1/search` and `/search` now search titles and bodies of visible questions
and published articles with PostgreSQL English full-text matching and bounded
responses governed by public visibility rules. English stemming, phrase/OR/exclusion
syntax, and relevance
ranking are supported. There is no typo correction, separate reply search, HTML
highlighting, or real-time push to other browsers.

## API contract and query syntax

`GET /api/v1/search?q=setup&page=0&size=20` is public and returns identical eligible
content for every role. Parameters default to empty q, page 0, size 20. Unknown or
repeated parameters return 400 INVALID_REQUEST. Invalid integers, negative pages,
sizes outside 1-100, and offsets above 2,147,483,647 return 400 INVALID_PAGE even for
blank queries. Trimmed q is bounded to 200 UTF-16 units; excess length returns
400 VALIDATION_FAILED with fieldErrors.q. Blank, punctuation-only, and stop-word-only
queries have no searchable tokens and return empty results, not a broad query.

The parser is explicitly `websearch_to_tsquery('english', q)` with q bound through
JDBC. Unquoted words combine with AND; double quotes request a phrase; OR combines
alternatives; a leading minus excludes a term. Other punctuation is processed by
PostgreSQL's text parser, rather than literal substring rules. For example,
`running` can match `run`, `"setup guide"` requests an ordered phrase, and
`setup -printer` excludes printer matches. Wildcard/prefix and raw tsquery syntax
are not supported. A negative-only query can match many eligible documents and
may have rank zero. See [PostgreSQL query parsing and ranking](https://www.postgresql.org/docs/18/textsearch-controls.html).

Each hit remains exactly `{kind,id,title,snippet,url,rank}`, in the standard
`{items,page,size,totalElements,totalPages}` envelope. Kind is ARTICLE or QUESTION.
URLs are relative `/knowledge/{slug}` or `/questions/{id}`. Rank is a finite,
nonnegative `ts_rank_cd` value, not a percentage or confidence score. Global order
is rank descending, then ARTICLE before QUESTION, then PostgreSQL UUID ascending.
The same UUID in different content kinds remains two distinct hits.

Snippets remain bounded plain-text body prefixes, at most 240 UTF-16 units including
an ellipsis, without splitting surrogate pairs. SQL projects at most 241 code
points and Java applies the UTF-16 bound. A deep body match may be outside the
prefix; snippets are neither match-centered nor highlighted. The UI renders titles
and snippets as text and rejects malformed hits or unsafe/mismatched result URLs.

## V9 vectors, indexes, and ranking

V9 adds a STORED generated search_vector to question and knowledge_article:

```sql
setweight(to_tsvector('english'::regconfig, title), 'A') ||
setweight(to_tsvector('english'::regconfig, body), 'B')
```

The migration computes vectors for existing V8 rows. Inserts and title/body edits
maintain them automatically, including direct SQL updates; there is no application
reindex call or trigger to forget. The explicit configuration makes vectors
independent of the session default. This follows the generated-column approach in
[PostgreSQL text-search tables and indexes](https://www.postgresql.org/docs/18/textsearch-tables.html).

Stored vectors cost storage and write-time computation, but avoid reparsing each
candidate document during reads. Generated columns keep this invariant in the
schema, rather than in application callbacks or custom triggers. Adding stored
columns and building indexes happens during normal Flyway startup; this migration
is not an online/concurrent-index deployment. V1-V8 are unchanged. Hibernate does
not map the derived column; PostgreSQL owns its value.

Partial GIN indexes cover VISIBLE questions and PUBLISHED articles. Visibility and
publication changes automatically update index membership. Hidden/draft vectors
still exist in private rows, but public predicates exclude them before counts,
ranking, and pagination. GIN locates matching candidates; ranking and global sorting
remain query work. `ts_rank_cd` uses title A weight 1.0 and body B weight 0.4. Controlled
comparable fixtures rank titles above body-only matches; frequency/proximity can
also affect the score, so not every title match must outrank every body match.
Concatenated vector positions can allow phrases across the title/body boundary.

## Visibility, consistency, and the browser

One shared UNION ALL expression filters eligible questions/articles for both count
and hits. Global LIMIT/OFFSET follows ranking and merge. SearchService retains its
read-only REPEATABLE_READ transaction, so a hide/archive committing between count
and items cannot create a mixed snapshot. Searches begun after commit see the new
state. Pages across separate requests may shift as content changes.

Visible questions on archived boards remain eligible. Hidden questions, draft and
archived articles, replies, report reasons, resolution notes, and audit records
contribute no public hits, snippets, or totals. Bodies now participate in matching;
only eligible public content participates.

`/search?q=...&page=...` supports direct loading, refresh, and back/forward history.
Submitting a new query resets page; the same query refreshes page zero. Blank,
loading, no-match, invalid-page, failure, and retry states remain explicit. The form
now explains English title/body matching and parser syntax instead of literal title
matching. It has labelled controls, visible focus, and a wrapping mobile layout.

Query keys include q/page and requests consume AbortSignal. Obsolete responses
cannot overwrite newer results; failed refetches hide stale text. Navigation/focus
refetches results. Existing question create/edit, moderation resolve/restore, and
article mutations invalidate the search prefix. Authentication changes clear caches.
Already-rendered text in another browser remains until a normal refresh/search;
there is no remote erasure promise.

## Verification and measured query plans

SearchIT covers body matching, comparable weighting, global mixed-kind rank/tie
pagination, every-role public parity, private title/body exclusion, archived-board
questions, phrases/OR/exclusion/punctuation/stemming, blank/stop words/no matches,
input bounds, Unicode-safe snippets, committed edits and visibility changes, and
snapshot consistency during concurrent archival. An isolated schema is migrated
to V8, populated, and upgraded to V9 to prove backfill, vector maintenance, index
creation, and repeatable migration. Fresh database tests now require V9.

The browser journey retains public paging and moderation/publication checks and
adds a body-only article, cross-page relevance ordering, phrase matching, immediate
live-body edits, stemming, and stop-word results. Component tests retain URL/history,
validation, safe decoding, plain text, cancellation, and late-response coverage.
See [verification record](verification.md) for actual test counts and status.

`scripts/measure-full-text-search.sql` creates 20,000 article and 20,000 question
fixtures in temporary tables with the actual generated-vector expressions and
partial GIN predicates, runs ANALYZE, and captures count/first-page plans before
rolling back. Application data is untouched. Measured on local PostgreSQL 18.6 on
2026-09-20:

- `selectivebeacon`: two eligible hits. Count 0.139 ms; hits 0.258 ms. Both sources
  used GIN bitmap index/heap scans; result sorting used 26 kB quicksort.
- `common`: 30,998 eligible hits. Count 80.352 ms; first 20 hits 1,822.290 ms. The
  planner chose sequential scans and a 42 kB top-N heapsort. Broad ranking remains
  substantially more work; forcing index scans is not justified by this evidence.

These single local observations are not production latency targets. Earlier
title-only measurements used a different dataset and do not establish a controlled
before/after speed comparison. The observed selective plans support retaining the
partial GIN indexes.
Full [captured plans](evidence/search-query-plans.txt) include query labels, rows,
buffer activity, and timings; the repeatable script records the fixture definitions.
Run with:

```powershell
Get-Content -Raw scripts/measure-full-text-search.sql |
  docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1'
```
