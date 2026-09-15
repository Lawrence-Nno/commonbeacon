# CommonBeacon

A customer-support community built with Java, Spring Boot, PostgreSQL, and React.

## Status

Stage 1 is complete: repository setup and all prerequisite checks passed, including a real Linux container run. See [development setup](docs/development-setup.md). Stage 2 is complete: the Spring Boot backend, PostgreSQL service, first migration, and seven passing integration tests are implemented. See [backend setup](docs/backend-setup.md) for run commands. Stage 3 is complete: a responsive React shell, live backend connection states, frontend tests, verification script, and initial CI jobs are implemented. See [frontend setup](docs/frontend-setup.md). Remote CI has not run for Stage 3 yet.

The first milestone delivers accounts, public boards, questions, replies, accepted solutions, server-enforced permissions, tests, and Docker Compose startup.

## Selected versions

- Java: Eclipse Temurin JDK 21.0.12.1 (Windows package 21.0.12.101).
- Node.js: 24.13.1; npm: 11.8.0. Pinned in `.nvmrc`.
- Git: verified with 2.53.0.windows.1.
- Docker Desktop: 4.91.0 selected; use its bundled Docker Engine and Compose.
- Spring Boot: 4.1.1; Maven Wrapper: 3.3.4; Maven: 3.9.16.
- PostgreSQL: 18.6-alpine3.24, pinned by digest in Compose and integration tests.
- React and React DOM: 19.3.0.
- TypeScript: 6.0.3; Vite: 8.3.0; React plugin: 6.1.1.
- React Router: 8.3.1; TanStack Query: 5.102.8.

Frontend dependencies are pinned in package.json and package-lock.json and passed installation, lint, type checking, tests, and a production build. TypeScript 6.0.3 satisfies the lint tooling peer range. Spring Boot manages backend dependency versions. REST comes first; GraphQL follows the verified core workflow.

## Local prerequisite check

In PowerShell, from this repository:

```powershell
. .\scripts\use-dev-tools.ps1
.\scripts\check-prerequisites.ps1
.\scripts\check-prerequisites.ps1 -RunContainer
```

The first command selects installed project tools for the current terminal only. The second checks versions and the Docker engine. The final command additionally pulls/runs a disposable `hello-world:latest` diagnostic container. It creates no project database or persistent volume.

Docker Desktop must be installed and running in Linux-container mode. Complete any first-launch setup before running the checks. See [setup and troubleshooting](docs/development-setup.md).

## Repository layout

- `backend/`: Spring Boot application, Flyway migration, Maven Wrapper, and integration tests.
- `frontend/`: React/TypeScript shell, Vite proxy, component/client tests, and browser smoke checks.
- `scripts/`: tool selection, database configuration, prerequisite checks, and combined verification.
- `docs/`: shared setup documentation.

Local planning documents are deliberately excluded from Git. Do not force-add them.

## Identity

CommonBeacon is an independent learning and portfolio project using original branding. The Java base package is `com.lawrencenno.commonbeacon`.

Private remote: https://github.com/Lawrence-Nno/commonbeacon
