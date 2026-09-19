# Milestone B evidence

## Status

Stage 1 is complete: baseline verified and implementation contracts finalized on
2026-09-19. Stages 2–13 are pending. Reports, moderation actions, articles, search,
and the operational summary remain implementation targets, not working features.

Baseline revision: `2d33c96de62b6feda06a4b32366e300844f7ff4a`.
Verification used the existing workspace with documentation changes, not a new
clean checkout. No backend/frontend source, dependency, migration, or verification
script changed during Stage 1. Pre-existing README, architecture, evidence-link,
and interview-preparation edits were preserved. Stage 1 changes are uncommitted.

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
that Stage 11 had not run remotely. It is baseline evidence only; the Stage 1
documentation changes have not been committed, pushed, or remotely tested.

## Limitations and next action

No Milestone B feature tests exist yet because Stage 1 establishes contracts.
No new clean-install, upgrade, persistence-restart, search performance, or
accept-versus-hide experiment was performed here; those belong to later stages.
The baseline's in-memory sessions, single-instance throttling, and board-level
write serialization remain unchanged. Java discussion prompts were documented;
personal rehearsal is not certified by test results.

Proceed to Stage 2: implement report persistence, submission, duplicate/visibility
rules, and member forms against the finalized moderation contract.
