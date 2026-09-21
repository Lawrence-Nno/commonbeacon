# Query-plan evidence

These captured PostgreSQL plans support the implementation decisions documented in
[search](../search.md) and [moderation](../moderation.md). General test coverage and
revision-specific results are in [verification](../verification.md).

Both captures were recorded locally on 2026-09-20 using PostgreSQL 18.6. They were
produced with temporary synthetic tables and rolled back; they contain query plans,
counts, and timings, not application accounts, credentials, or customer content.
The plan output is preserved unchanged. Hardware, cache state, data distribution,
and concurrent work affect timings; these are not production benchmarks.

## Search

[search-query-plans.txt](search-query-plans.txt) captures 20,000 question and 20,000
article fixtures using the generated-vector expressions and partial GIN predicates.
The selective query returns two eligible matches, with count/hits execution times
of 0.139/0.258 ms and GIN bitmap scans. The broad query returns 30,998 eligible
matches, with count/first-page times of 80.352/1,822.290 ms and sequential scans.
The broad result demonstrates the remaining ranking/sorting cost.

Reproduce against the local Compose database:

```powershell
Get-Content -Raw scripts/measure-full-text-search.sql |
  docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1'
```

## Operational summary

[operational-summary-query-plan.txt](operational-summary-query-plan.txt) captures
10,000 questions, 10,000 replies, 6,000 reports, and 3,000 articles in temporary,
reduced-column tables. The exact counts are 6,000 unanswered questions, 2,000 open
reports, and 1,000 published articles. The query took 5.826 ms using a hash anti-join
and sequential scans. This does not establish a latency bound as data grows.

```powershell
Get-Content -Raw scripts/measure-operational-summary.sql |
  docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1'
```

Run commands from the repository root with the database available. The scripts
create only temporary fixtures and finish with ROLLBACK; they do not alter stored
community data. They still consume database resources while running. The fixture
SQL is the source of truth for dataset generation and query definitions.
