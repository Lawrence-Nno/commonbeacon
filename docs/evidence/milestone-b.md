# Milestone B evidence

## Status

Stage 1 is complete: baseline verified and implementation contracts finalized on
2026-09-19. Stage 2 report submission is implemented and locally verified, including
the final container/browser workflow. Stages 3–13 remain pending.
Moderation decisions, articles, search, and summary remain future features.

Baseline revision: `2d33c96de62b6feda06a4b32366e300844f7ff4a`.
Verification used the existing workspace with documentation changes, not a new
clean checkout. No backend/frontend source, dependency, migration, or verification
script changed during Stage 1. Pre-existing README, architecture, evidence-link,
and interview-preparation edits were preserved. These paragraphs record the Stage 1
baseline; its later commit/push and Stage 2 work are recorded below.

## Stage 1 contracts and inspection

- [Moderation](../moderation.md): actor permissions, exact report and privileged
  context DTOs, duplicate reports, resolution decisions, restoration, audit
  history, reviewed versions, errors, transaction order, and dashboard semantics.
- [Knowledge](../knowledge.md): administrator lifecycle, immutable slugs,
  publication timestamps, public/private DTOs, errors, and the frozen search
  contract. Search implementation documentation will be added in Stage 8.
- `MILESTONE_B_PLAN.md`: Stage 1 decisions finalized; subsequent stages remain pending.
- `IMPLEMENTATION_GUIDE.md`, `MILESTONE_A_PLAN.md`, and the
  [Milestone A evidence](milestone-a.md): corrected stale Stage 11 remote status.

Inspected board/question/reply services and repositories, security routing,
validation/error mapping, article/moderation absence, current migrations, auth
cache handling, verification scripts, isolated Compose configuration, and browser
configuration. Only V1–V5 currently exist; V6 is available for report persistence.
Check again before implementation and never alter an applied migration.

The key Stage 4 prerequisite is `QuestionService.update`: it loads a managed
question before taking its board lock. Existing versioning prevents silent stale
writes, but the moderation design requires fresh locked visibility/ownership
checks. Documented the change to scalar lookup -> board -> question, and the full
moderation order board -> question -> reply if applicable -> report if resolving.
No production transaction was changed during Stage 1. Acceptance/reply paths were
also inspected; future hidden-row reads must use separate privileged queries.

## Fresh local baseline verification

Environment: Windows PowerShell, Temurin JDK 21.0.12.101 selected by
`scripts/use-dev-tools.ps1`, Node 24.13.1, pinned npm 11.8.0 from the existing tool
installation, Docker Engine 29.8.0, and the project's pinned PostgreSQL 18.6 image.
Docker Desktop was initially stopped and was started for these checks.

From the repository root, with Docker/child-process access outside the restricted
sandbox:

```powershell
. ./scripts/use-dev-tools.ps1
./scripts/verify.ps1
```

Result: exit 0. Observed output:

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Tests  49 passed (49)
All backend and frontend checks passed.
```

The 59 Java tests comprise 5 unit and 54 PostgreSQL/HTTP integration tests.
Frontend lint, TypeScript checking, 49 tests, and production build all passed.
Full local output: `%TEMP%/commonbeacon-b-stage1-verify-ready.log`; Maven XML/text
reports remain in ignored `backend/target/surefire-reports` and `failsafe-reports`.

For the isolated browser suite:

```powershell
. ./scripts/use-dev-tools.ps1
$env:COMMONBEACON_E2E_PROJECT = 'commonbeacon-e2e-b-stage1-20260919'
# Native stderr includes ordinary Docker progress; judge the native exit code.
$ErrorActionPreference = 'Continue'
Push-Location frontend
try {
    npm.cmd run test:smoke
    $testExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($testExit -ne 0) { throw "Browser suite failed: $testExit" }
```

Result: exit 0, `6 passed (15.8s)`. The runner built the backend/frontend images,
waited for healthy services, and exercised real sessions/CSRF, registration,
administration, multi-member questions/replies/acceptance and stale edits, public
reloads, mobile keyboard/layout checks, and intentional upstream-outage handling.
The outage fixture's connection-refused log messages are expected test behavior.

The stack used `compose.e2e.yaml` with PostgreSQL tmpfs and its explicit project
name; no development volume was mounted or targeted. Logs showed all three test
containers and the project network removed. Browser output is in
`%TEMP%/commonbeacon-b-stage1-browser-retry.log`; HTML report/screenshots and
`backend-compose.log` are under ignored `frontend/playwright-report` and
`frontend/test-results`. No new UI was added and no separate manual visual review
is claimed; the existing automated browser suite was rerun.

### Initial failed attempts and resolution

1. `verify.ps1` stopped before frontend checks because Testcontainers could not
   find a Docker environment. The first sandbox run and a subsequent unrestricted
   run both failed. `docker info` outside the sandbox confirmed the Linux engine
   pipe was absent, and Docker processes were not running. Starting Docker Desktop
   restored engine access; the full verification then passed without source fixes.
2. The first browser invocation inherited `$ErrorActionPreference = 'Stop'` from
   the tool-selection script while redirecting native stderr. PowerShell stopped
   on Docker's normal `Image ... Building` progress. Reinvoked with native stderr
   treated as output and explicitly propagated the process exit code; all six
   tests and cleanup passed. No repository runner modification was necessary.
3. The first GitHub query was blocked by the sandbox proxy. The same read-only
   query with network access succeeded.

The configured RCA instruction file `C:/Users/USER/.Codex/issue-rca.md` was absent
when read. Diagnosis used observed process/log/engine evidence; no missing-file
instructions were assumed. Failed-attempt logs remain in TEMP alongside the
successful logs; no unsuccessful attempt is counted as passing verification.

## Remote CI inspection

On 2026-09-19 ran:

```text
gh run list --repo Lawrence-Nno/commonbeacon --limit 5 --json databaseId,headSha,status,conclusion,url,workflowName
gh run view 35100360402 --repo Lawrence-Nno/commonbeacon --json headSha,status,conclusion,url,jobs
```

[Run 35100360402](https://github.com/Lawrence-Nno/commonbeacon/actions/runs/35100360402)
completed successfully for the exact baseline SHA above on 2026-09-16. Inspected
all three job results: backend/PostgreSQL integration tests, frontend quality
checks, and container images/real browser journey. The browser job's artifact
upload and isolated cleanup steps also succeeded. This corrects the older claim
that Stage 11 had not run remotely. This was baseline evidence only at the Stage 1
handoff; the later Stage 1 commit/push and remote results are recorded below.

## Stage 1 handoff limitations and next action (historical)

No Milestone B feature tests existed at that handoff because Stage 1 established contracts.
No new clean-install, upgrade, persistence-restart, search performance, or
accept-versus-hide experiment was performed here; those belong to later stages.
The baseline's in-memory sessions, single-instance throttling, and board-level
write serialization remain unchanged. Java discussion prompts were documented;
personal rehearsal is not certified by test results.

At the Stage 1 handoff, the next action was Stage 2. Its implementation follows.

## Stage 1 commit/push and CI follow-up

- Committed/pushed the ignore rule, moderation/knowledge contracts, Stage 1 evidence,
  and only the Stage 1 Milestone A evidence corrections as `d90ab67`. Unrelated
  interview-preparation changes, including the evidence-page link, stayed unstaged.
- [Run 35454701087](https://github.com/Lawrence-Nno/commonbeacon/actions/runs/35454701087)
  passed backend and frontend jobs but failed the existing question browser journey
  while waiting for a login form after closing the previous page.
- Source inspection found navigation/page closure immediately after clicking Sign
  out, before its asynchronous CSRF/logout requests and UI transition completed.
  Added assertions waiting for the Sign in link before navigating or closing.
  This changes test synchronization, not application logout behavior.
- Committed/pushed that isolated two-line fix as `4da9779`.
  [Run 35455367702](https://github.com/Lawrence-Nno/commonbeacon/actions/runs/35455367702)
  passed all three jobs (backend/PostgreSQL, frontend, container/browser). The six
  existing journeys also passed locally with the fix during Stage 2 verification.

## Stage 2 implementation and local checks: 2026-09-19

Stage 2 changes are currently uncommitted; local source is based on `4da9779` plus
the report implementation. No Stage 2 remote verification is claimed.

Added V6 content_report storage with real target/reporter/resolver foreign keys,
exactly-one-target and resolution-state checks, UTF-16-aware reason bounds, and
separate partial unique indexes for open reports on questions and replies. No
existing migration was edited. Resolution metadata is stored for later stages;
no queue, resolve, or restore endpoint has been added.

Added authenticated `POST /api/v1/reports`, service-level authorization, session-derived
reporter, DTO-local unknown-field rejection, board -> question -> reply locking,
post-lock visibility checks, duplicate conflicts, and minimal no-store receipts.
Reporting is allowed on archived boards and does not mutate public content.

Added question/reply report forms, typed response decoding, report ProblemDetail
handling, pending-submit protection, retained failed drafts, and account/target-keyed
form state. Existing edit and acceptance controls retain their behavior.

Final `./scripts/verify.ps1` passed with exit 0:

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
Tests run: 62, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Tests  58 passed (58)
All backend and frontend checks passed.
```

That is 67 Java tests including 8 new ReportIT cases, and 58 frontend tests
including 9 report cases. Lint, type checking, and production build passed.
After the browser-test synchronization/rate-limit adjustments, frontend lint and
type checking passed again. Log: `%TEMP%/commonbeacon-b-stage2-verify-final.log`.

The report integration tests cover real sessions, all signed-in roles, self-reports,
archived boards, missing/hidden targets and parents, forged fields, invalid reasons,
CSRF, unauthenticated direct service calls, concurrent duplicate submissions on both
target types, independent reporters, database constraints, reporting after a resolved
record, and public response privacy. A synchronized PostgreSQL test holds the board
lock, observes both report requests waiting, hides the parent, commits, and proves
both requests return 404 without inserting reports. Unicode boundary cases prove
HTTP validation and database length checks agree.

Frontend tests cover trimmed submission and CSRF headers, 400/401/404/409/500 and
network failures, preserved reasons, duplicate-submit disablement, malformed receipts,
reply-only targets, and draft/late-result isolation after account switching.

### Verification issues addressed

- First backend run: the copied report fixture shared the reply suite's cached
  application context and exceeded the real login limiter during setup (429).
  Gave ReportIT its own demo-password configuration/context. Production limits
  were not changed; all integration tests then passed.
- Review found Java/JavaScript UTF-16 bounds could disagree with PostgreSQL
  character counts for emoji. Updated the new, uncommitted V6 constraints and
  verified both minimum and maximum supplementary-character boundaries on a
  fresh disposable database. Existing V1–V5 migrations remain untouched.
- First expanded browser run: six existing tests passed; the new report journey's
  second login hit the shared Nginx-address rate limit. Added one bounded retry
  honoring the server's Retry-After header, retaining real authentication and
  throttling. The test has an explicit overall timeout. Cleanup still removed
  the failed run's isolated project.
- Docker image packaging was slow but completed successfully; no build timeout,
  dependency change, or packaging workaround was required. Final browser results
  below must come from the rebuilt image containing the final V6 migration.
- The configured `C:/Users/USER/.Codex/issue-rca.md` remains absent. Observed logs
  and source were used to diagnose failures; no absent instructions were assumed.

### Final browser workflow and visual inspection

Ran `npm run test:smoke` from `frontend` with the session tool selector and
`COMMONBEACON_E2E_PROJECT=commonbeacon-e2e-b-stage2-final-20260919`, judging native
exit status rather than normal Docker stderr progress. Exit 0: **7 passed (1.3m)**.
The report test honored the real login limiter's 60-second Retry-After once.
Both application images built successfully; the backend included the final V6.
Log: `%TEMP%/commonbeacon-b-stage2-browser-final.log`.

The new browser journey reports both a question and a reply on an archived board,
reloads and checks a duplicate rejection preserves the reason, verifies visitors
have no reporting controls, checks public JSON contains no private reasons or
resolution data, and confirms members cannot open the moderator queue. It exercises
Tab/Enter submission and verifies no horizontal overflow at 390px.

Inspected the generated `report-mobile.png` in the report test's ignored
`frontend/test-results` directory: label, textarea, private-report guidance, actions,
and archived-board context are readable and contained in the narrow layout. The
existing full-page screenshot also captures the skip-to-content link; no new visual
styling or manual interactive-browser inspection is claimed.

Runner logs show all test containers and the project network removed; subsequent
Docker label-filter queries returned no remaining containers or networks for this
project. Failure and successful runs used only disposable PostgreSQL tmpfs; no
development database cleanup or rebuild was performed.

Stage 2 remains uncommitted and has not run remotely. Stage 1 plus its browser-test
fix is pushed and green remotely. Next: Stage 3, privileged report queue/context.
