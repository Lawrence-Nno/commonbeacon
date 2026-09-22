# Testing and verification

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
  These do not yet provide end-to-end export/import workflows.
- Deployment and migrations: clean V1-V10 migration, populated V5-to-V10 and V9-to-V10 upgrades,
  repeated migration, Hibernate validation, backend/database restarts, normal
  Compose down/up, and cleanup after an intentionally injected failure.

The [OpenAPI contract](openapi.json) describes 39 application operations and 44
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
