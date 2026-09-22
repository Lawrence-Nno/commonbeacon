# Architecture decisions

## One backend and one database

A single Spring Boot application owns identity, boards, conversations, moderation,
knowledge, and search. Controllers validate transport input; services enforce roles,
ownership, and transactional rules; repositories select explicit response data.
Keeping these features together allows content, accepted answers, reports, and
visibility history to change atomically in PostgreSQL. Separate services would
introduce cross-service consistency and operational costs without a current need.

The React client uses same-origin session APIs through Nginx. UI permissions improve
usability; service and HTTP authorization enforce access even when clients bypass
buttons. Public author projections expose only ID and display name.

## Sessions and deployment boundary

Spring Security manages server-side sessions and rotates session IDs on login.
The browser uses an HttpOnly, SameSite=Lax cookie. Writes require session-backed
CSRF tokens, including registration and login. Local HTTP disables Secure in the
local profile; the production profile enables it. Sessions and address-based login
limits live in memory: backend restart signs users out, and multiple instances
would require shared state. A proxy shares its socket-address login allowance
among users until trusted deployment proxy handling is designed.

## Schema ownership and accepted-answer integrity

Flyway owns versioned SQL; Hibernate validates rather than modifies the schema.
Applied migrations are immutable. Foreign keys and checks protect invariants even
when code is bypassed. The composite foreign key
`question(id, accepted_reply_id) -> reply(question_id, id)` ensures the selected
reply belongs to its question. One nullable reference gives at most one selection;
public solved status additionally requires that reply to be visible.

V1-V5 establish the community; V6-V7 add reports and visibility history; V8 adds
articles; V9 adds generated search vectors and partial GIN indexes; V10 adds private
transfer metadata; V11 adds authorization revisions and download leases. Clean startup and forward upgrades from populated V5 and V9
are tested with schema validation. This does
not establish reverse migrations, old-binary compatibility, or zero-downtime upgrades.
V9's stored-column/index work occurs during Flyway startup.

## Transfer job and artifact boundary

Transfer jobs use short PostgreSQL transactions with one coordination-row lock
before job and requester locks. Leases and monotonically increasing fences keep
stale workers from publishing or advancing checkpoints. File work runs outside
these transactions, after durable intent; a completion ledger and artifact metadata
publish atomically. Private files use generated keys outside the application tree.
The store and reconciliation scheduler are disabled by default. See
[storage and recovery](data-transfer-storage.md) for guarantees and limitations;
[current permissions and requester job/download APIs](data-transfer-access.md) are
implemented. Export/import creation workflows remain unavailable.

## Moderation atomicity and concurrency

Thread mutations acquire locks in order: board, question, target reply if present,
then report when resolving. Scalar lookups locate IDs before fresh locked entity
reads; preloaded managed state must not determine decisions after a lock wait.
Permissions, visibility, archival rules, and reviewed versions are rechecked under
those locks. Board locks coordinate archival but serialize unrelated writes on
one board, a deliberate throughput limitation.

`expectedVersion` protects reviewed intent; JPA `@Version` guards persistence
updates; constraints protect structure. Locks alone cannot detect a stale form.
Hiding an accepted reply clears selection and writes the visibility event in the
same transaction as report resolution. A failure rolls back all three. Hiding a
parent preserves child states and internal acceptance; restoring a reply does not
reaccept it. Moderation remains available on archived boards without permitting
ordinary member writes. PostgreSQL tests cover both accept/hide lock orders and
injected failures, with exact stored-state and event-count assertions.

## Reports and visibility history are separate

Reports capture a member's concern and its resolution. Partial unique indexes
allow only one OPEN report per reporter and target. Other reporters remain
independent; resolving one report does not resolve the rest. DISMISS and
ACKNOWLEDGE_HIDDEN update report metadata without inventing a visibility change.
HIDE and RESTORE append to `moderation_action` only when visibility changes.

Private context and history require moderator/administrator access and no-store
responses. Public DTOs omit report text and hidden content. History is append-only
through application behavior, not tamper-proof against database administrators.
This separation preserves what was reported independently of later content actions.

## Knowledge lifecycle

Administrators create DRAFT articles, publish them once, and archive drafts or
published articles. Published edits become public immediately; there is no separate
working revision. ARCHIVED is terminal. Slug and original author remain immutable.
Fresh row locks plus reviewed versions prevent stale edits or transitions. Article
writes do not acquire thread/report locks. Public queries select PUBLISHED rows
before pagination and never expose private lifecycle fields.

## PostgreSQL full-text search

PostgreSQL owns stored generated title/body vectors with explicit English parsing,
title weight A and body weight B. Partial GIN indexes cover only eligible content.
This avoids an external search service and asynchronous indexing consistency work.
`websearch_to_tsquery` is bound as data; `ts_rank_cd` ranks the merged question and
article candidates before global pagination. GIN reduces candidate lookup, not
ranking/sorting cost. Broad queries can legitimately use sequential scans.

Public search, article pages, and compound moderation reads use consistent snapshots
for counts and items. The operational summary uses one aggregate SQL statement.
Separate page requests can move as content changes. English stemming, no typo
correction, body-prefix snippets, and no reply search are explicit limits.
See [measured search plans](search.md#verification-and-measured-query-plans).

## Loading, caching, and verification

JPA relationships are lazy, with explicit entity graphs/projections for DTOs.
Conversion occurs within service transactions; open-in-view is disabled. Responses
never serialize entities or lazy proxies. Client query keys include the actor for
private data; logout, expiry, and account switching cancel and clear cached data.
Failed refetches hide stale private content. Other browsers refresh on navigation
or focus; there is no real-time remote erasure promise.

Nginx serves the client and proxies `/api`; Docker service names are internal.
Ordinary shutdown retains the PostgreSQL named volume. Browser tests use an isolated
tmpfs database; persistence checks use a separate disposable named volume. Unit,
real PostgreSQL integration, browser, migration, and restart checks verify different
boundaries. [Verification](verification.md) records exact revisions,
counts, CI status, and remaining operational limits.
