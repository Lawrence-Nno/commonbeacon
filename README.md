# CommonBeacon

A customer-support community built with Java, Spring Boot, PostgreSQL, and React.

## Status

Stage 1 is complete: repository setup and all prerequisite checks passed, including a real Linux container run. See [development setup](docs/development-setup.md). Stage 2 has not started: there is no backend application, database service, migration, or frontend application yet.

The first milestone delivers accounts, public boards, questions, replies, accepted solutions, server-enforced permissions, tests, and Docker Compose startup.

## Selected versions

- Java: Eclipse Temurin JDK 21.0.12.1 (Windows package 21.0.12.101).
- Node.js: 24.13.1; npm: 11.8.0. Pinned in `.nvmrc`.
- Git: verified with 2.53.0.windows.1.
- Docker Desktop: 4.91.0 selected; use its bundled Docker Engine and Compose.
- Spring Boot: 4.1.1, to be pinned in Stage 2 with Maven Wrapper.
- PostgreSQL: major 18; exact container patch/digest will be selected and tested in Stage 2.
- React and React DOM: 19.3.0.
- TypeScript: 7.0.2; Vite: 8.3.0; React plugin: 6.1.1.
- React Router: 8.3.1; TanStack Query: 5.102.8.

Frontend versions were checked against npm registry engine and peer metadata. They are selected, not installed or build-tested; Stage 3 will generate the manifest and lockfile and verify the complete frontend dependency graph. Spring Boot manages backend dependency versions in Stage 2. REST comes first; GraphQL follows the verified core workflow.

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

- `backend/`: reserved for the Stage 2 Spring Boot application.
- `frontend/`: reserved for the Stage 3 React application.
- `scripts/`: local tool selection and prerequisite checks.
- `docs/`: shared setup documentation.

Local planning documents are deliberately excluded from Git. Do not force-add them.

## Identity

CommonBeacon is an independent learning and portfolio project using original branding. The Java base package is `com.lawrencenno.commonbeacon`.

Private remote: https://github.com/Lawrence-Nno/commonbeacon
