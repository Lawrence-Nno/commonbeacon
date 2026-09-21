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

Install the Node version in `.nvmrc` with npm 11.8.0. On Windows, the helper can
select an nvm-windows installation for the current terminal without changing
other projects' runtime:

```powershell
. .\scripts\use-dev-tools.ps1
.\scripts\check-prerequisites.ps1 -RunContainer
```

The helper also accepts explicit `-JavaHome` and `-NodeHome` directories for nonstandard installations. It does not download tools or change persistent environment variables.

The check script validates Java runtime/compiler versions, pinned Node/npm versions, Git, Compose, and a reachable Linux Docker engine. It exits nonzero on failure. The optional container smoke check uses a floating diagnostic image only; application container versions and digests are pinned in the Compose and Docker files.

## Troubleshooting

- **Tool not found:** start a new terminal after installation or dot-source the helper.
- **Wrong Java version:** pass the Temurin 21 installation directory to `-JavaHome`.
- **Wrong Node version:** select the version in `.nvmrc`; do not silently use another project's Node version.
- **Docker engine unavailable:** open Docker Desktop and wait for engine readiness. A successful CLI version check alone is insufficient.
- **WSL or virtualization error:** follow Docker's Windows prerequisites and complete required OS setup; do not skip integration tests to hide an unavailable engine.
- **Registry download failure:** check network/proxy access; no successful container test is claimed until the image actually runs.

## Verification

See [verification](verification.md) for repeatable commands, coverage, recorded
results, and current limitations.

## Sources

- [Spring Boot 4.1.1 system requirements](https://docs.spring.io/spring-boot/system-requirements.html).
- [Vite Node requirements](https://vite.dev/guide/).
- [Docker Desktop Windows installation](https://docs.docker.com/desktop/setup/install/windows-install/).
- [Temurin installation](https://adoptium.net/installation/).
