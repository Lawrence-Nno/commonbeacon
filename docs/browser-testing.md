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
builds both production images, starts Nginx, the real backend, and disposable PostgreSQL, waits for readiness, runs
Playwright, saves logs, and tears down its test stack. For one file:

```powershell
npm run test:smoke -- questions.spec.ts
```

Ports 4173 (containerized Nginx) and 4174 (intentional Vite outage) must be free.
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
The Stage 11 workflow runs the same command, uploads reports/traces/logs, and has an always-run cleanup step. The runner accepts only commonbeacon-e2e-* names for its optional CI project override.

## Development demo content

Opt-in local seeding creates three onboarding boards with three lessons each.
Every lesson has one accepted explanation and four labeled misconceptions with
corrections: nine questions and 45 visible replies. The existing private report,
hidden reply/history, and published/draft article examples remain. See the
[onboarding walkthrough](demo-walkthrough.md) for roles, counts, and restart rules.
Seeding remains off by default and excluded from the prod profile. The onboarding
browser test verifies all lesson counts and selected answers through public APIs,
checks that alternative answers are clearly labeled, and inspects mobile layout.

The report browser journey uses its own target IDs and captures baseline counts;
it tolerates pre-existing seeded reports. It covers member reporting, duplicates,
stale moderation tabs, reply/parent hide and restoration, acceptance clearing,
overview deltas, and logout/account changes. The article journey covers failed
saves without lost drafts, publication/search/overview changes, live body updates,
stale-tab reconciliation, archival disappearance, and long unbroken mobile content.
Both retain role checks and keyboard/viewport assertions. The remaining journeys
cover registration/session expiry, the original ask/reply/accept flow, unavailable
backend states, search ordering/privacy, and account-scoped overview data.

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
- Stage 10 was pushed as 97bab88. See [Stage 11 fresh-copy evidence](evidence/milestone-a.md); its new workflow requires a push before remote execution can be verified.
