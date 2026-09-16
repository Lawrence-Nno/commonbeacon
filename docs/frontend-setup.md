# Stage 3: frontend and verification

## What exists

React and TypeScript provide a responsive community home, an about route, and a not-found route. The home lists public boards; each board shows paginated questions, with creation and owner editing available on open boards. See [question workflows](questions.md). See [boards and demo setup](boards.md) for Stage 5 administration. Stage 4 adds registration and login routes; see [authentication](authentication.md).

The connection card calls the real backend through Vite. It supports loading, healthy, offline, timeout, and retry states. A failed refresh replaces previously cached success; automatic checks run every 30 seconds while the tab is active.

## Start the application

Use Node 24.13.1 and npm 11.8.0, pinned by the root .nvmrc and frontend package engines.

Terminal 1, from the repository root:

```powershell
. .\scripts\use-dev-tools.ps1
. .\scripts\use-local-database.ps1
docker compose -f compose.yaml -f compose.host.yaml up -d --wait --wait-timeout 120 db
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run
```

This requires the ignored .env file from [backend setup](backend-setup.md). If a backend is already running on port 8080, use that instance instead of starting a second one.

Terminal 2, from the repository root:

```powershell
. .\scripts\use-dev-tools.ps1
Set-Location frontend
npm.cmd ci
npm.cmd run dev
```

Open http://127.0.0.1:5173. Stop the foreground frontend with Ctrl+C.

## Request flow

1. React asks the typed HTTP client for /api/health.
2. The browser sends a same-origin request to Vite.
3. Vite forwards that request to http://127.0.0.1:8080/actuator/health.
4. Spring Boot checks health, including database connectivity.
5. The client validates the response shape and the connection card displays its state.

The proxy rewrites only the health route; other /api requests preserve their path. No CORS relaxation or browser-side backend hostname is needed. BACKEND_URL is an optional server-side environment variable for a different local backend; it is not a frontend secret.

The HTTP client supports session authentication and fresh CSRF headers for mutations. It sends same-origin credentials, bounds requests to eight seconds, preserves caller cancellation, and translates errors into a consistent ApiError. Auth endpoints expose controlled ProblemDetail messages and field errors; other raw server error bodies are not displayed.

## Quality checks

Install frontend dependencies first with npm ci, then run from the repository root:

```powershell
. .\scripts\use-dev-tools.ps1
.\scripts\verify.ps1
```

The script runs Maven verify, frontend lint, TypeScript checking, Vitest, and the production build. It stops on the first failed command and exits nonzero. It detects the operating system through .NET, not the optional OS environment variable, and uses native exit codes rather than interpreting stderr warnings as failures.

Individual commands from frontend:

```powershell
npm.cmd run lint
npm.cmd run typecheck
npm.cmd run test:run
npm.cmd run build
```

npm run test starts interactive test watching. The production output is frontend/dist. Vite preview serves those static files but does not provide the development API proxy; use npm run dev for the connected local application until the production reverse proxy is added.

## Real-browser checks

Run npm run test:smoke from frontend with Docker Desktop and Chrome available. Stage 10 starts a real backend with disposable PostgreSQL automatically; no development backend or .env is needed. See [isolated browser verification](browser-testing.md) for commands, isolation, coverage, and failure artifacts.

## Dependency decisions

React/React DOM 19.3.0, React Router 8.3.1, TanStack Query 5.102.8, Vite 8.3.0, and the React plugin 6.1.1 match the selected stack.

TypeScript changed from the provisional 7.0.2 selection to 6.0.3 because typescript-eslint 8.70.0 supports TypeScript below 6.1. jsdom 26.1.0 was selected because the newest jsdom requires a newer Node patch than the pinned 24.13.1. No force-install or ignored peer constraints were used.

All direct package versions are exact. package-lock.json records the full resolved graph. Changes to dependencies must update both the manifest and lockfile.

## Continuous integration

.github/workflows/ci.yml defines two jobs on GitHub-hosted Ubuntu:

- Backend: Temurin 21, Docker availability check, Maven verify with Testcontainers.
- Frontend: pinned Node and npm, npm ci, lint, type checking, component/client tests, and production build.

The jobs use read-only repository permissions. Browser smoke checks are currently local because they require a running backend. Stage 3 [remote CI passed](https://github.com/Lawrence-Nno/commonbeacon/actions/runs/35004794571). Stage 4 changes have not run remotely.

## Verification evidence

On 2026-09-15:

- Frontend lint, TypeScript checking, 15 component/client tests, and production build passed.
- Three Google Chrome smoke tests passed, using a real backend and an intentionally unavailable upstream.
- Desktop (1440px) and mobile (390px) screenshots were inspected.
- Isolated temporary command fixtures verified early exit on backend and frontend failures and success when a command emits stderr warnings but exits 0.
- Combined scripts/verify.ps1 completed with exit 0: 7 backend integration tests, 15 frontend tests, lint, type checking, and build all passed.

The evidence above records Stage 3. See [authentication](authentication.md) for Stage 4 behavior and verification. See [questions](questions.md) and [replies](replies.md) for discussion features and current verification results.

## References

- [GitHub Actions: Maven](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven).
- [GitHub Actions: Node.js](https://docs.github.com/en/actions/tutorials/build-and-test-code/nodejs).
