# Run CommonBeacon with Docker Compose

This is a localhost development deployment. Docker Desktop must be running Linux
containers. Java and Node are only needed for host development and test tooling;
the images build the application from source.

## Start

Copy .env.example to .env if it does not exist. Set a non-placeholder
POSTGRES_PASSWORD. Keep existing credentials when reusing an existing database.
Then, from the repository root:

```powershell
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
docker compose ps
```

Open http://127.0.0.1:8081. Change FRONTEND_PORT in .env if necessary.
Readiness waiting is bounded; the initial image downloads/build may take longer.
Demo accounts are optional: see [boards](boards.md) for DEMO_SEED_ENABLED and
DEMO_PASSWORD. Without demo seeding, register a member; administrator provisioning
is separate from registration.

## Network and cookies

Only the frontend port is published, bound to localhost. Nginx serves compiled
React assets, returns index.html for client routes, and proxies /api to the
backend. /api/health maps to the backend health endpoint. Missing assets return
404 rather than HTML. Nginx re-resolves Docker DNS after backend recreation.

Inside Compose, PostgreSQL is db:5432 and Spring Boot listens on all container
interfaces at backend:8080. Browser requests use one origin, so session cookies
and CSRF work without CORS configuration. The local Spring profile permits HTTP
cookies; HttpOnly, SameSite=Lax, and CSRF protection remain enabled. This file is
not an Internet production deployment: production needs TLS and secure cookies.

For host Java/Vite development, publish the database explicitly:

```powershell
docker compose -f compose.yaml -f compose.host.yaml up -d --wait db
. ./scripts/use-dev-tools.ps1
. ./scripts/use-local-database.ps1
```

The override also provides optional localhost backend port 8080 when starting
the backend service. Do not run a host backend and a published container backend
on the same port. Host JDBC uses 127.0.0.1 and POSTGRES_PORT; container JDBC always
uses db:5432.

## Logs, restart, shutdown

```powershell
docker compose logs --tail 100 backend frontend db
docker compose restart backend
docker compose up -d --wait --wait-timeout 180
docker compose down
```

Ordinary down preserves the named postgres_data volume. Do not use down --volumes
unless you intend to erase local data. Restarting the backend signs users out
because sessions are held in memory; accounts, questions, replies, and accepted
solutions remain in PostgreSQL.

PostgreSQL 18 stores data under /var/lib/postgresql/18/docker; the named volume
mount remains /var/lib/postgresql, as recommended by the
[official image documentation](https://hub.docker.com/_/postgres).
Changing POSTGRES_PASSWORD does not change the password inside an existing volume.

## Build and readiness design

Both Dockerfiles use separate build/runtime stages and digest-pinned official
base images. The backend builds with the Maven wrapper and runs with a JRE as a
non-root user. The frontend uses npm ci and serves its production build with Nginx.
Build contexts exclude .env and generated files. Container builds skip backend
tests; run scripts/verify.ps1 for the complete test suite.

PostgreSQL must be healthy before backend startup; backend health must pass
before frontend startup. The backend check calls Actuator with a bounded timeout.
The frontend health check tests Nginx; /api/health checks the application/database.

## Troubleshooting

- Unhealthy database: inspect db logs and verify existing-volume credentials.
- Backend startup failure: inspect backend logs for migration or connection errors.
- Port conflict: set FRONTEND_PORT, or stop the process occupying that port.
- Proxy 502 after recreation: Docker DNS refreshes within 10 seconds; check backend health.
- Signed out after restart: expected; sign in again.
- Host Java cannot connect: start db with compose.host.yaml, not the default stack alone.
- Never paste docker compose config output with resolved credentials; use --quiet.

## Repeatable persistence check

Use a disposable stack; no running development backend or local credentials are needed:

```powershell
. ./scripts/use-dev-tools.ps1
cd frontend
npm run test:persistence
npm run test:failure-cleanup
```

Requires Docker and installed frontend dependencies; port 4175 must be free.
The standalone `compose.persistence.yaml` uses a project-scoped named volume and
`.env.example`, never the development `.env` or volume. The runner creates an OPEN
report, a RESOLVED/HIDE report with audit history, a hidden formerly accepted reply,
and DRAFT/PUBLISHED/ARCHIVED articles using real session/CSRF APIs through Nginx.
It compares fingerprints of every stored row and verifies summary, visibility,
article states, and full-text results after backend restart, database restart,
and ordinary Compose down/up. Backend restart must expire the old session.

Only final cleanup removes the runner's disposable volume. The project prefix is
validated, existing projects are rejected, readiness is bounded, and cleanup runs
on failure too. The failure-cleanup command deliberately fails after creating
durable records, requires a nonzero child exit, and verifies no test containers,
network, or volume remain. Logs are saved under ignored `frontend/test-results`.
Optional `COMMONBEACON_PERSISTENCE_PROJECT` must start with
`commonbeacon-persistence-`; CI uses an exact run/attempt name.

The legacy `node scripts/verify-compose.mjs --allow-restart` command remains an
explicit development-volume check. It creates records in your development data
and restarts your app. Use the isolated commands above for routine verification.

## Schema compatibility

`MilestoneUpgradeIT` verifies both an empty database and V1-V5 fixtures upgraded
through V10. It preserves users, credentials, board archival, question/reply bodies,
versions, timestamps, hidden states, and accepted selections; checks generated
search vectors and representative constraints; and starts the current application
with Hibernate `ddl-auto=validate`. A separate populated V9-to-V10 check compares
all seven domain tables before and after adding transfer metadata. Applied migrations are unchanged.

This verifies forward upgrade to the current application. It does not establish
that an older binary can run against V10, nor provide reverse migrations or
an application rollback guarantee. A database backup/restore procedure is separate
from these tests; do not attempt rollback by editing Flyway history or applied SQL.

## Verification

See [verification](verification.md) for repeatable commands, coverage, recorded
results, and current limitations.
