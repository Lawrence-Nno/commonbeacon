# Frontend development and verification

## What exists

React and TypeScript provide a responsive community home, an about route, and a not-found route. The home lists public boards; each board shows paginated questions, with creation and owner editing available on open boards. See [question workflows](questions.md). See [boards and demo setup](boards.md) for board administration. Registration and login routes are implemented; see [authentication](authentication.md).

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

npm run test starts interactive test watching. The production output is frontend/dist. Vite preview serves those static files but does not provide the development API proxy; use npm run dev for host development or the Nginx-backed Compose deployment for the packaged application.

## Real-browser checks

Run npm run test:smoke from frontend with Docker Desktop and Chrome available. The runner starts a real backend with disposable PostgreSQL automatically; no development backend or .env is needed. See [isolated browser verification](browser-testing.md) for commands, isolation, coverage, and failure artifacts.

## Dependency decisions

React/React DOM 19.3.0, React Router 8.3.1, TanStack Query 5.102.8, Vite 8.3.0, and the React plugin 6.1.1 match the selected stack.

TypeScript changed from the provisional 7.0.2 selection to 6.0.3 because typescript-eslint 8.70.0 supports TypeScript below 6.1. jsdom 26.1.0 was selected because the newest jsdom requires a newer Node patch than the pinned 24.13.1. No force-install or ignored peer constraints were used.

All direct package versions are exact. package-lock.json records the full resolved graph. Changes to dependencies must update both the manifest and lockfile.

## Continuous integration

.github/workflows/ci.yml defines three jobs on GitHub-hosted Ubuntu:

- Backend: Temurin 21, Docker availability check, Maven verify with Testcontainers.
- Frontend: pinned Node and npm, npm ci, lint, type checking, component/client tests, and production build.
- Containers/browser: image builds, browser workflows, persistent-storage checks, injected-failure cleanup, and retained failure artifacts.

The jobs use read-only repository permissions. Container verification includes report uploads and always-run cleanup. See [Verification](verification.md) for exact remote-versus-local status.

## Verification

See [verification](verification.md) for repeatable commands, coverage, recorded
results, and current limitations.

## References

- [GitHub Actions: Maven](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven).
- [GitHub Actions: Node.js](https://docs.github.com/en/actions/tutorials/build-and-test-code/nodejs).
