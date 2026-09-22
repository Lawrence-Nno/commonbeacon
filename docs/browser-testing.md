# Isolated browser verification

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
loads development credentials. Disposable credentials are sample, fixed, and
used only by this localhost test backend.

Company-export storage is enabled in the backend container's private writable
directory. No artifact volume is mounted; removing the disposable container removes
its archives. Production deployments still require the durable storage overlay.

Cleanup uses down --remove-orphans on that generated project, without volume
deletion. It cannot name or mount the development volume. If the process is forcibly
killed, inspect docker compose ls and remove only the abandoned commonbeacon-e2e-*
project using compose.e2e.yaml. Never use development-stack cleanup commands for tests.

## Coverage

The administrator data-management journey creates a real company export, verifies
ZIP manifest/checksums, and checks private option defaults, keyboard confirmation,
mobile overflow, logout clearing, and member denial. It injects expired confirmation
and a lost success response, then verifies that explicit retries reuse one request
key and do not create a duplicate job. The real worker, private store and protected
download endpoint complete the successful path.

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
The CI workflow runs the same browser command, uploads reports/traces/logs, and has an always-run cleanup step. The runner accepts only commonbeacon-e2e-* names for its optional CI project override.

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

Run `npm run test:persistence` and
`npm run test:failure-cleanup`, using a separate disposable named volume on port
4175. They complement the browser stack's tmpfs database by checking restarts,
normal down/up, expired sessions, data preservation, and intentional failure
cleanup. See [persistence verification](compose.md#repeatable-persistence-check).
CI runs both after the full browser suite and retains their logs in the browser
artifact. Its always-run cleanup attempts both test projects even if one cleanup
fails. The legacy development-volume check is not invoked by these commands.

## Recorded results

See [verification](verification.md) for repeatable commands, coverage, recorded
results, and current limitations.
