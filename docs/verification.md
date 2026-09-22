# Testing and verification

Backend operational logging is covered by `RequestLoggingTest` and
`OperationalLoggingIT`: JSON output, correlation across security and MVC,
safe exception diagnostics, HTTP semantics, response resets, and MDC cleanup.
See [logging operations](logging.md).

CommonBeacon uses unit tests, real PostgreSQL integration tests, browser workflows,
and disposable deployment checks. Each covers a different boundary; a passing
build does not establish production readiness or a performance service level.

## Run the checks

Install Java 21, Node 24.13.1, npm 11.8.0, Docker with Linux containers, and Chrome.
The [development setup](development-setup.md) describes Windows tool selection.
For application startup without host build tools, use [Docker Compose](compose.md).

From the repository root in PowerShell:

```powershell
. ./scripts/use-dev-tools.ps1
cd frontend
npm ci
cd ..
./scripts/verify.ps1
cd frontend
npm run test:smoke
npm run test:persistence
npm run test:failure-cleanup
```

On Linux/macOS with those tools installed, run the underlying checks:

```sh
./backend/mvnw -f backend/pom.xml --batch-mode --no-transfer-progress verify
cd frontend
npm ci
npm run lint
npm run typecheck
npm run test:run
npm run build
npm run test:smoke
npm run test:persistence
npm run test:failure-cleanup
```

The backend tests use Testcontainers, not the development database. The browser
runner builds an isolated Compose stack using ports 4173 and 4174 and a tmpfs
database. Storage checks use port 4175 and their own disposable named volume.
Run these suites sequentially with the ports free. They do not read development
`.env` credentials or mount the development database volume. Docker must be
available; an unavailable engine is a failure, not a skipped integration check.

Backend reports are under `backend/target/surefire-reports` and
`backend/target/failsafe-reports`. Browser reports, screenshots, traces, and container
logs are under `frontend/playwright-report` and `frontend/test-results`. Generated
artifacts are ignored by Git. See [browser verification](browser-testing.md) and
[persistence verification](compose.md#repeatable-persistence-check) for isolation,
cleanup, and failure handling.

## Coverage

- Identity and community: session rotation/expiry, CSRF, role and owner boundaries,
  registration races, boards, question/reply edits, accepted solutions, archival,
  pagination, database constraints, and stale-write conflicts.
- Moderation: duplicate reports, hidden context privacy, report resolution,
  acceptance clearing, independent parent/reply visibility, restoration without
  reacceptance, and private visibility history. PostgreSQL tests verify rollback
  and both accept-versus-hide lock orders using real database locks.
- Knowledge and search: administrator-only lifecycle, immutable author/slug,
  public draft/archive exclusion, live edits, publication conflicts, weighted
  English matching, global pagination, snippets, and snapshot consistency.
- Client behavior: validation, draft recovery, actor-scoped caches, logout/expiry,
  late-response handling, keyboard navigation, mobile layout, and SPA deep links.
- Transfer foundations: versioned archive fixtures, privacy and reference validation,
  durable idempotency, competing/recreated workers, lease fencing, private filesystem
  permissions, quota admission, publication replay, corruption and cleanup recovery.
  Real HTTP tests also cover current roles, requester isolation, CSRF, scoped
  password grants, ticket replay/expiry, cancellation retries and in-flight revocation.
  Company export now has API-to-ZIP, snapshot consistency, recovery and durable
  artifact checks. Personal export and live import workflows remain future work.
- Deployment and migrations: clean V1-V13 migration, populated V5-to-V13, V9-to-V13 and V11-to-V13 upgrades,
  repeated migration, Hibernate validation, backend/database restarts, normal
  Compose down/up, and cleanup after an intentionally injected failure.

The [OpenAPI contract](openapi.json) describes 51 application operations and 51
schemas. [API documentation](api.md#checking-the-contract) gives an optional
structural-validation command. Schema validation alone does not prove permissions,
transaction behavior, or server-specific UTF-16 length bounds.

## Recorded verification

These are observations for specific revisions, not continuously updated test counts.
Consult the [Verify workflow](../.github/workflows/ci.yml) and the CI run for the
revision you intend to deploy.

At `fb5f585627d63790e8d850327f8f220cab9e413d`, local verification on 2026-09-20
passed 122 backend tests (5 unit and 117 integration), 127 frontend tests,
lint/type checking/build, and 11 browser tests. Persistent-storage and injected-
failure cleanup checks also passed. All three jobs passed in
[CI run 35533157796](https://github.com/Lawrence-Nno/commonbeacon/actions/runs/35533157796).

The subsequent documentation/API-contract revision
`9b8f72c67c9302a280ea8d9f626ec207d9f42b5f` also passed all three jobs in
[CI run 35564924579](https://github.com/Lawrence-Nno/commonbeacon/actions/runs/35564924579).
Later changes are not covered by those historical CI runs.

On 2026-09-21, a source-only copy of the application was started with the documented
Compose procedure using fresh disposable credentials, a separate project, and
port 4181. No development `.env`, dependency directory, or host build output was
copied; normal Docker layer caches were available. All services became healthy.
A one-off live contract check validated 114 HTTP responses spanning all 39 API
operations, including denied roles, duplicates, stale versions, publication/search,
and moderation visibility. The initial seed had three boards, nine questions,
45 visible answers, nine accepted answers, and overview counts 0/1/1.

A backend restart invalidated sessions while exact fingerprints across all seven
domain tables remained unchanged. Report/action history, hidden content, acceptance,
article lifecycle state, counts, and search remained consistent after signing in.
Chrome inspection at 390 x 844 confirmed the overview, archived article editor,
and restored question displayed correctly without horizontal overflow. Cleanup
removed only the disposable project's containers, network, and volume.

Those live-response checks were session verification utilities, not a maintained
additional suite. To reproduce the behavior, use the
[operator walkthrough](operator-walkthrough.md), maintained browser tests, and
persistence commands above. Local screenshots and temporary logs are not durable
release artifacts. CI artifacts have a seven-day retention period and may expire;
the repeatable scripts and committed tests are the primary verification mechanism.

On 2026-09-22, local Stage 5 imported-identity changes based on
`7d8c1160b86cb9d04c0d3dd9b99d205308dbda7a` passed `scripts/verify.ps1`:
184 backend tests (31 unit and 153 integration), 132 frontend tests, lint,
type checking and build. All 11 browser smoke tests, persistence/restart/down-up
checks, and injected-failure cleanup passed on disposable resources. Coverage
includes inactive-author constraints, contact isolation, session/worker revocation,
and populated V11-to-V12 upgrades preserving credentials and roles. These local
results do not claim a CI run or a complete archive-import workflow.

On 2026-09-22, local Stage 6 company-export changes based on Stage 5 commit
`3825fe6` passed `scripts/verify.ps1`: 197 backend tests (31 unit and 166 integration),
132 frontend tests, lint, type checking and build. All 11 browser tests passed.
The expanded disposable persistence suite generated a company export through the
scheduled worker/API and verified identical protected archive bytes after backend
and database restarts and Compose down/up. Directory mode 0700 and cleanup of both
disposable volumes, including injected-failure cleanup, passed. Export tests cover
snapshot consistency, independent private options, invalid source data, crash
recovery, limits, revocation and HTTP delivery. OpenAPI JSON and local references
were checked; the optional full specification validator was not installed. These
are local working-tree results, not remote CI or measured production capacity.

On 2026-09-22, the administrator export screen based on `571b861` passed
`scripts/verify.ps1`: 203 backend tests (35 unit and 168 integration), 149 frontend
tests, lint, type checking and production build. All 12 isolated browser tests
passed, including a real company ZIP download and manifest/file checksum checks.
The transfer journey covers expired confirmation, safe replay after a lost success
response, keyboard operation, 390px layout, logout and member denial. Desktop/mobile
captures were visually inspected. Frontend tests also cover late actor responses,
cache clearing, lifecycle states, cancellation versions and bounded downloads.
The browser stack stores artifacts only in its disposable backend container and
was removed successfully. No development data or storage configuration changed.
Persistence/restart checks were not rerun for this frontend-only slice; the Stage 6
storage evidence above remains separate. These are local results, not remote CI.

Download feedback follow-up (2026-09-22): all 152 frontend tests, lint and type
checking passed. The isolated real-download browser journey passed again, including
production image builds, inline password confirmation, ZIP checksums and mobile
completion feedback. Controlled streams verify byte progress, interruption, stopping,
and that only complete downloads are handed to the browser. No backend change.

On 2026-09-22, Stage 8 personal exports based on `6649b5c` passed
`scripts/verify.ps1`: 211 backend tests (35 unit and 176 integration), 155 frontend
tests, lint, type checking and production build. All 13 isolated browser tests
passed, including real company and personal ZIPs, checksums, requester ownership,
report-field exclusions and logout clearing. The personal mobile capture was
visually inspected. Integration tests cover exact multi-user projections, hidden
content, empty accounts, snapshot consistency, revocation, forged scope/ownership,
expired download tickets and early rejection by the company-import codec boundary.
The existing company-export persistence suite passed backend/database restarts and
Compose down/up against the shared worker; it is not a separate personal restart
test. Injected-failure cleanup also passed and removed its disposable resources.
OpenAPI JSON/local references and diff whitespace checks passed; the optional
full specification validator was not installed. Upload/import remains unimplemented.
These are local working-tree results, not remote CI or production-capacity evidence.

Export-history presentation follow-up (2026-09-22): main company/personal pages
now request only the latest job; separate history routes provide compact, paginated
rows with one expanded detail panel. All 157 frontend tests, lint, type checking
and production build passed. Both isolated export browser journeys passed with
real ZIP downloads, history navigation, keyboard expansion, logout clearing and
390px layout checks. The mobile history capture was visually inspected. An existing
article test now awaits its field after editor navigation/reload. No backend,
storage or retention changes; backend/persistence suites were not rerun for this UI
change. Evidence: export-history-tests.log and export-history-browser.log.

Transfer-capacity fix (2026-09-22): finished jobs now reserve only retained
published download bytes after temporary/uncertain artifacts are confirmed deleted.
Reconciliation before admission recovers old completed-export reservations; active
jobs retain the 768 MiB working reservation. The shared quota error now explains
that shared transfer capacity is full. All 35 backend unit tests and 55 targeted
integration tests (foundation, company/personal exports and transfer access) passed.
New cases cover retained archives allowing personal admission, active-capacity
rejection, pending cleanup, download leases and final deletion. Maven failsafe
verification passed separately after PowerShell treated a routine Mockito warning
as an error in the initial wrapper. No schema migration or frontend changes.
Local backend rebuild passed. Startup reconciliation reduced the two existing
company-export reservations from 1,610,612,736 to 26,672 bytes while retaining both
available archives. Evidence: capacity-final-verify.log, capacity-deploy.log and
Maven XML reports. These are local results.

Stage 9 quarantine upload/inspection (2026-09-22), based on `8563bd3`, passed final
`scripts/verify.ps1`: 248 backend tests (60 unit and 188 integration), 157 frontend
tests, lint, type checking and production build. All 14 isolated browser tests passed.
New coverage checks hostile ZIP metadata and names, actual inflated/streamed byte
limits, compression ratio, private-profile rejection, owner/admin/CSRF/grant checks,
interrupted uploads, exhausted storage, cancellation, revocation, expiry and safe
reports. Proxy tests cover the route-specific 64 MiB allowance and unchanged ordinary
API limit. Inspection never authorizes activation or writes domain records/mappings.

The expanded persistence suite retained a native company export, its quarantine
inspection result/digest and domain fingerprints across backend/database restarts
and Compose down/up. Injected-failure cleanup passed. Populated V13 exports/audit
records survive V14; existing V5/V9/V11 upgrade tests pass. Old migration-count
assertions were corrected before the final run. OpenAPI JSON/local references and
unique operation IDs were validated; the optional full specification validator was
not installed. Diff whitespace checks passed. Evidence: stage9-verify-final.log,
stage9-smoke.log, stage9-persistence.log and stage9-cleanup.log. These are local
working-tree results, not remote CI or production capacity certification. Staging,
target-state review, activation and the upload UI remain subsequent stages.

## Query-plan evidence

The [evidence directory](evidence/README.md) contains captured PostgreSQL plans for
search and operational counts, together with reproduction instructions and dataset
sizes. These use temporary synthetic fixtures and rollback; their single-run
measurements are diagnostic, not production latency promises.

## Limits

The supplied deployment is localhost HTTP. Public hosting requires TLS, trusted
proxy configuration, credential/backup management, and deployment hardening.
Sessions and login throttling are single-instance; backend restart signs users out.
Board locks serialize writes within a board. Email verification, password recovery,
MFA, shared sessions, and distributed rate limiting are not implemented.

Forward migration and storage retention are tested. Reverse migration, older-binary
compatibility with newer schemas, rolling upgrades, backup restoration, and sustained
production load are not verified. Visibility history is append-only through the
application, not tamper-proof against database administrators. Search uses English
stemming without typo correction or separate reply search. Counts are snapshots;
other sessions refresh through navigation/focus rather than real-time push.


Stage 10 staging/dry-run review (2026-09-22), based on pushed `cdf215e`, passed the
full verification script: 60 backend unit tests, 197 integration tests, 157 frontend
tests, lint, type checking and production build. Final staging-count and terminal
upload cleanup refinements then passed 60 unit and 51 targeted integration tests
(17 import, 15 transfer access, 19 transfer lifecycle). The combined inventory is
258 backend tests. Fresh schema and populated V14 upgrade coverage preserve prior
inspection data and validate V15 against the current application.

All 14 browser journeys passed, including a real proxy upload, dry-run request,
safe populated-target review and cancellation with unchanged live boards. The
persistence suite retained staged payload fingerprints and the review digest across
backend/database restarts and Compose down/up. Its injected-failure run confirmed
that the real exported archive produces only the expected target eligibility error
and that the disposable containers, network and volumes are removed. OpenAPI's 57
operation IDs are unique and all local references resolve.

These checks cover deterministic retries, expired-lease recovery, no live writes,
full graph/state errors, timestamp order, duplicate origins, identity/local-ID
collisions, stale target/mapping/payload reviews, extra staged rows, all warning
acknowledgements, cancellation/expiry cleanup and reservation release. Activation,
its exclusive migration gate and measured production capacity remain later work.
