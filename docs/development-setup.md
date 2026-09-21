# Development setup

## Prerequisites

Use Java 21, Node 24.13.1 with npm 11.8.0, Git, and Docker Desktop with its WSL 2 Linux backend. Exact selected application versions are recorded in the root README.

The JDK includes the Java compiler and JVM. The compiler converts Java source to bytecode; the JVM executes it. Maven resolves dependencies and runs the build; Spring Boot configures and starts the application. The checked-in Maven Wrapper pins the build tool, so a global Maven installation is unnecessary.

## Windows setup

Install Eclipse Temurin JDK 21 and Docker Desktop from their publishers or Windows Package Manager:

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --exact --source winget
winget install --id Docker.DockerDesktop --exact --source winget
```

Complete installer prompts and any requested restart. Launch Docker Desktop, complete its first-run setup, and select Linux containers with WSL 2.

Node 24.13.1 is already installed on the development machine through nvm-windows. The default shared Node version was 20.20.2. To avoid changing other projects' selected runtime, use the session-only helper:

```powershell
. .\scripts\use-dev-tools.ps1
.\scripts\check-prerequisites.ps1 -RunContainer
```

On another machine, install the version in `.nvmrc` first. The helper also accepts explicit `-JavaHome` and `-NodeHome` directories for nonstandard installations. It does not download tools or change persistent environment variables.

The check script validates Java runtime/compiler versions, pinned Node/npm versions, Git, Compose, and a reachable Linux Docker engine. It exits nonzero on failure. The optional container smoke check uses a floating diagnostic image only; application container versions and digests are pinned in the Compose and Docker files.

## Troubleshooting

- **Tool not found:** start a new terminal after installation or dot-source the helper.
- **Wrong Java version:** pass the Temurin 21 installation directory to `-JavaHome`.
- **Wrong Node version:** select the version in `.nvmrc`; do not silently use another project's Node version.
- **Docker engine unavailable:** open Docker Desktop and wait for engine readiness. A successful CLI version check alone is insufficient.
- **WSL or virtualization error:** follow Docker's Windows prerequisites and complete required OS setup; do not skip integration tests to hide an unavailable engine.
- **Registry download failure:** check network/proxy access; no successful container test is claimed until the image actually runs.

## Verification record

Initial inspection on 2026-09-15 found:

- Git 2.53.0.windows.1 with `main` tracking the private CommonBeacon remote.
- Node 24.13.1 and npm 11.8.0 available by explicit installation path.
- WSL 2.5.9.0, default distribution Ubuntu, default WSL version 2.
- Java and Docker initially absent; installation and runtime verification were initiated during Stage 1.

Final verification on 2026-09-15 passed:

- Eclipse Temurin runtime and compiler 21.0.12.1 installed and verified.
- Node 24.13.1 and npm 11.8.0 selected through the session-only helper.
- Docker Desktop 4.91.0 installed; Docker CLI 29.8.0 and Compose 5.5.1 verified.
- Docker Linux engine reachable; disposable hello-world container completed successfully.
- Diagnostic image digest: `sha256:5e23090353324d887c48ad5e5c56d294eab81588df9605b07d1afe895f9cc8f8`.
- The prerequisite script initially returned exit 1 while the engine was stopped, then exit 0 after Desktop started and the container ran.
- Git diff whitespace check passed; local planning documents, credentials, Java build output, and frontend dependencies remain ignored.

This record covers Stage 1 prerequisites. Stages 2 and 3 are now implemented; see backend-setup.md and frontend-setup.md for their separate verification.

## Sources

- [Spring Boot 4.1.1 system requirements](https://docs.spring.io/spring-boot/system-requirements.html).
- [Vite Node requirements](https://vite.dev/guide/).
- [Docker Desktop Windows installation](https://docs.docker.com/desktop/setup/install/windows-install/).
- [Temurin installation](https://adoptium.net/installation/).

Frontend package versions and compatibility constraints were read from the public npm registry on 2026-09-15; subsequent install/build and CI results are recorded in the milestone evidence.
