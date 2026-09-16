# Isolated browser verification (Stage 10)

## Run

Start Docker Desktop in Linux-container mode and install Chrome. With frontend
dependencies installed:

```powershell
. ./scripts/use-dev-tools.ps1
cd frontend
npm run test:smoke
```

No running development backend, .env, or demo password is required. The runner
builds the real backend, starts disposable PostgreSQL, waits for readiness, runs
Playwright, saves logs, and tears down its test stack. For one file:

```powershell
npm run test:smoke -- questions.spec.ts
```

Ports 4180 (test backend), 4173 (Vite), and 4174 (intentional outage) must be free.
Existing servers are not reused. Run one browser suite at a time on this machine.
The single-worker suite keeps account activity within normal login rate limits;
it does not disable authentication protections.

## Data isolation

compose.e2e.yaml is standalone, not an override of compose.yaml. PostgreSQL uses
tmpfs at /var/lib/postgresql, with no named or external volume. Each run receives
a generated commonbeacon-e2e-* project name. All startup, log, and cleanup calls
specify that project and file. The runner uses .env.example explicitly; it never
loads development credentials. Disposable credentials are fictional, fixed, and
used only by this localhost test backend.

Cleanup uses down --remove-orphans on that generated project, without volume
deletion. It cannot name or mount the development volume. If the process is forcibly
killed, inspect docker compose ls and remove only the abandoned commonbeacon-e2e-*
project using compose.e2e.yaml. Never use development-stack cleanup commands for tests.

## Coverage

The real HTTP/PostgreSQL suite covers registration, separate member/admin browser
sessions, question creation, replies, acceptance/replacement/clearing, reload and
logout; forbidden edits, CSRF, stale drafts, archived boards, and externally expired
sessions. The connected question journey registers both participating members.

The same-browser account switch checks that prior owner controls and private
drafts disappear. An intentionally aborted publish request checks draft preservation,
actionable failure feedback, and retry. This failure injection is confined to that
one request; the successful journey uses real persistence.

Layout checks cover empty boards, 2,000-character unbroken content at 390px,
keyboard skip navigation and visible focus, public/mobile pages, and unavailable
backend retry states. Screenshots, failure traces, and backend-compose.log are in
ignored frontend/test-results.

scripts/verify.ps1 runs the separate Java and frontend regression suites.
Browser CI orchestration and artifact upload are Stage 11.

## Development demo content

Opt-in local seeding now creates two fictional questions and replies in Getting
started: one solved and one awaiting acceptance. IDs derive from stable seed keys,
and timestamps are fixed. Reseeding does not rewrite existing conversations,
credentials, board settings, hidden states, or accepted-answer choices.
Seeding remains disabled by default and excluded from the prod profile.
See boards.md for enabling it. Rebuild/restart your local backend to see new seeds.

The older scripts/verify-compose.mjs check remains a separate, explicitly
restart-enabled development-volume persistence check from Stage 9. It is not
invoked by npm run test:smoke.

## Verification (2026-09-16)

- scripts/verify.ps1 passed: 5 backend unit tests, 54 PostgreSQL/HTTP integration tests, 49 frontend tests, lint, type checking, and production build.
- All 6 Playwright tests passed against the isolated stack (25.3 seconds for the browser suite).
- Runtime inspection confirmed PostgreSQL tmpfs at /var/lib/postgresql and no mounted volumes.
- Generated test containers/network were removed automatically; the development stack remained healthy.
- Mobile question and outage screenshots were inspected; long-content overflow and visible keyboard-focus assertions passed.
- Refreshed the development backend and confirmed both seeded conversations and their solved/unanswered states through the public API.
- Stage 10 remains uncommitted. Browser CI orchestration is Stage 11.