# CommonBeacon

An independent customer-support community built to learn Java: Spring Boot,
PostgreSQL, React/TypeScript, and Docker Compose.

Milestone A implements accounts, boards, questions, replies, accepted solutions,
owner/role permissions, conflict handling, and repeatable tests. Milestone B adds
member reporting, a protected moderator queue, atomic report resolution, and
content hiding/restoration with private audit history. Administrator knowledge-article
APIs and screens support drafts, publication, live edits, and archival. Browse
published guides at `/knowledge`; administrators manage them at `/admin/articles`.
See the milestone evidence
for local verification and remote CI status.

## Start from a fresh checkout

Prerequisites: Git and Docker Desktop running Linux containers. The application
builds Java and frontend assets inside containers.

```powershell
git clone https://github.com/Lawrence-Nno/commonbeacon.git
cd commonbeacon
Copy-Item .env.example .env
```

Edit .env: replace POSTGRES_PASSWORD with a unique local password. For fictional
demo accounts, set DEMO_SEED_ENABLED=true and a unique DEMO_PASSWORD of 12–128
characters. Then:

```powershell
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
```

Open **http://127.0.0.1:8081**. Without demo seeding, use Join the community to
register a member. Registration never grants administrator privileges.

```powershell
docker compose logs --tail 100 backend frontend db
docker compose down
```

Ordinary shutdown preserves data. Backend restart signs users out. Never add
--volumes unless intentionally deleting local data. This is a localhost HTTP
deployment, not an Internet production configuration.

## Verify

Host checks require Java 21, Node 24.13.1, npm 11.8.0, Docker, and Chrome.
On this Windows setup, use the tool-selection helper; on other systems install
the pinned tools and run the same Maven/npm commands.

```powershell
. ./scripts/use-dev-tools.ps1
cd frontend
npm ci
cd ..
./scripts/verify.ps1
cd frontend
npm run test:smoke
```

verify.ps1 runs Java unit/PostgreSQL integration tests and frontend lint, type
checking, tests, and build. test:smoke builds both container images and runs the
real browser journey against an isolated, temporary database. It never uses .env
or the development volume. No host backend is needed.

## Demo accounts

With opt-in demo seeding, all four initially use DEMO_PASSWORD:

- alex.member@example.test — member.
- sam.member@example.test — member.
- morgan.moderator@example.test — member capabilities, report review, resolution, hiding/restoration, and audit history.
- avery.admin@example.test — board administration and article creation, editing, publication, and archival through Manage articles.

Getting started contains two fictional conversations: one solved and one
unanswered. Reseeding preserves edits, credentials, and solution choices.

## Documentation

- [Compose startup, persistence, troubleshooting](docs/compose.md)
- [Host tool setup](docs/development-setup.md) and [host backend](docs/backend-setup.md)
- [Isolated browser tests](docs/browser-testing.md)
- [REST API examples](docs/api.md)
- [Architecture decisions and Java learning notes](docs/architecture.md)
- [Milestone evidence, demo script, and limitations](docs/evidence/milestone-a.md)
- [Reporting and moderator review](docs/moderation.md), [Milestone B evidence](docs/evidence/milestone-b.md)
- [Knowledge-article screens, APIs, and search contracts](docs/knowledge.md)
- [Authentication](docs/authentication.md), [boards](docs/boards.md),
  [questions](docs/questions.md), [replies](docs/replies.md), [solutions](docs/accepted-solutions.md)

## Stack and repository

Java 21; Spring Boot 4.1.1; Maven wrapper 3.9.16; PostgreSQL 18.6;
React 19.3; TypeScript 6.0.3; Vite 8.3; React Router 8.3.1;
TanStack Query 5.102.8. Dependency locks and container digests are in source.

backend/ contains the Java application, Flyway migrations, and integration tests.
frontend/ contains the client and browser tests. scripts/ contains host helpers.
.github/workflows/ci.yml defines backend, frontend, and container/browser jobs.

Local planning documents, .env, build output, and test artifacts are ignored.
Never force-add credentials or planning documents. Branding and demo content are
original and fictional. Java package: com.lawrencenno.commonbeacon.
