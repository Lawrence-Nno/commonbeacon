# Host backend and database setup

## What exists

One Spring Boot 4.1.1 application targeting Java 21, built by Maven 3.9.16 through the official Maven Wrapper. PostgreSQL runs in Docker; this guide runs the backend on the host for development. For the complete container deployment, see [Compose](compose.md).

Flyway applies V1-V10. Hibernate validates the mapped schema and never changes it. The backend provides session authentication, community discussions, moderation, knowledge articles, and search; see [REST APIs](api.md). V10 adds internal [transfer job and storage infrastructure](data-transfer-storage.md); transfer endpoints are not available yet.

The PostgreSQL image is pinned to 18.6-alpine3.24 and digest `sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2`. Compose and integration tests use the same image. PostgreSQL 18 stores data beneath `/var/lib/postgresql`, where the named volume is mounted.

## First-time setup

From the repository root in PowerShell:

```powershell
. .\scripts\use-dev-tools.ps1
Copy-Item .env.example .env
```

Only copy the example when `.env` does not already exist. Replace the password in `.env` with a local random value. This file is ignored by Git. Use unquoted literal values without interpolation or inline comments so Compose and the host helper interpret them consistently.

Then:

```powershell
. .\scripts\use-local-database.ps1
docker compose config --quiet
docker compose -f compose.yaml -f compose.host.yaml up -d --wait --wait-timeout 120 db
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run
```

The datasource helper sets only process environment variables. It uses the same database credentials and port as Compose, without printing the password. The backend requires datasource configuration; there is no embedded database fallback.

Open [backend health](http://127.0.0.1:8080/actuator/health). A healthy application returns `status: UP` and may include health-group names. Details and other Actuator endpoints are not exposed. The server binds to localhost by default, to keep host development access local.

Stop the foreground backend with Ctrl+C. To stop the database while preserving its data:

```powershell
docker compose down
```

Do not add `--volumes` to routine shutdown. Changing initialization credentials in `.env` does not update credentials inside an existing PostgreSQL volume; perform a deliberate database credential change instead.

If port 5432 is occupied, select another `POSTGRES_PORT` in `.env`, rerun the datasource helper, and recreate the Compose database service without deleting the volume.

## Build and tests

Docker Desktop must be running in Linux mode. Tests need neither the development database nor `.env`:

```powershell
. .\scripts\use-dev-tools.ps1
.\backend\mvnw.cmd -f backend/pom.xml --batch-mode --no-transfer-progress verify
```

Surefire owns unit-test discovery. Failsafe runs `*IT` integration tests during `verify` and fails if it finds none. Unit tests and PostgreSQL integration tests are separate suites; current counts are recorded in [Verification](verification.md).

The integration suite starts its own PostgreSQL container with generated test connection details, applies Flyway, and exercises the real HTTP health endpoint and JPA mapping. It checks migration repeatability, unique normalized emails, invalid roles, and blank hashes. Docker absence is a test failure, not an automatic skip.

Reports are under `backend/target/failsafe-reports/`. Successful verification also builds `backend/target/commonbeacon-0.0.1-SNAPSHOT.jar`.

## Schema decisions

- UUID user IDs are supplied by the identity service.
- Emails must already be lowercase and trimmed; the database enforces that invariant and uniqueness. Registration normalizes and validates email syntax.
- Roles are stored as strings constrained to MEMBER, MODERATOR, and ADMINISTRATOR.
- UTC creation timestamps default in PostgreSQL and map to Java Instant.
- Password storage is hash-only; registration uses the configured PBKDF2 password encoder.
- Applied migrations are immutable. Add a new migration for subsequent schema changes.
- The development database role can perform migrations. Deployment-specific least-privilege roles are future work.

## Application initialization

Spring creates and connects configured components through dependency injection. Configuration supplies environment-specific connection details. Flyway runs before JPA initialization, so schema validation checks the migrated schema. Transactional registration and session identity are implemented; see [authentication](authentication.md).

## Verification

See [verification](verification.md) for repeatable commands, coverage, recorded
results, and current limitations.

## Official references

- [Spring Boot Testcontainers integration](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html).
- [Official PostgreSQL image and volume layout](https://hub.docker.com/_/postgres).
