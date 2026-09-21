# CommonBeacon

CommonBeacon is a customer-support community for asking questions, sharing answers,
and publishing reliable product guidance. Members can mark accepted solutions and
report concerns; moderators review reports and manage content visibility;
administrators organize boards and maintain the knowledge library.

## Capabilities

- Public boards, questions, answers, and published knowledge articles.
- Account registration, session authentication, owner editing, and accepted answers.
- Private reporting, moderator review, restoration, and visibility-change history.
- Administrator-managed article drafts, publication, live edits, and archival.
- English full-text search across question and article titles and bodies.
- Protected operational counts for unanswered questions, open reports, and published articles.

## Start from a fresh checkout

Prerequisites: Git and Docker Desktop running Linux containers. The application
builds Java and frontend assets inside containers.

```powershell
git clone https://github.com/Lawrence-Nno/commonbeacon.git
cd commonbeacon
Copy-Item .env.example .env
```

Edit .env: replace POSTGRES_PASSWORD with a unique local password. For optional
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

For restart and storage checks, run `npm run test:persistence` and
`npm run test:failure-cleanup` from `frontend`. These use a separate disposable
volume and localhost port 4175; see [persistence verification](docs/compose.md#repeatable-persistence-check).

## Demo accounts

With opt-in demo seeding, all four initially use DEMO_PASSWORD:

- alex.member@example.test — member.
- sam.member@example.test — member.
- morgan.moderator@example.test — member capabilities, report review, resolution, hiding/restoration, and audit history.
- avery.admin@example.test — board administration and article creation, editing, publication, and archival through Manage articles.

Getting started, Product help, and Using CommonBeacon each contain three practical
onboarding questions. Each question has five visible answers: one accepted
explanation and four clearly labeled misconceptions with corrections (nine
questions and 45 visible answers in total). Private moderation examples and the
published/draft knowledge articles support the operator walkthrough.
Reseeding preserves later edits, credentials, selections, and moderation decisions.
See the [onboarding walkthrough](docs/demo-walkthrough.md) and
[operator verification walkthrough](docs/operator-walkthrough.md).

## Documentation

- [Compose startup, persistence, troubleshooting](docs/compose.md)
- [Host tool setup](docs/development-setup.md) and [host backend](docs/backend-setup.md)
- [Isolated browser tests](docs/browser-testing.md)
- [REST API examples](docs/api.md) and [OpenAPI contract](docs/openapi.json)
- [Architecture decisions](docs/architecture.md)
- [Testing, verification results, and limitations](docs/verification.md)
- [Reporting and moderator review](docs/moderation.md)
- [Knowledge-article screens, APIs, and search contracts](docs/knowledge.md)
- [Weighted full-text search, visibility, and query plans](docs/search.md)
- [Authentication](docs/authentication.md), [boards](docs/boards.md),
  [questions](docs/questions.md), [replies](docs/replies.md), [solutions](docs/accepted-solutions.md)

## Stack and repository

Java 21; Spring Boot 4.1.1; Maven wrapper 3.9.16; PostgreSQL 18.6;
React 19.3; TypeScript 6.0.3; Vite 8.3; React Router 8.3.1;
TanStack Query 5.102.8. Dependency locks and container digests are in source.

backend/ contains the Java application, Flyway migrations, and integration tests.
frontend/ contains the client and browser tests. scripts/ contains host helpers.
.github/workflows/ci.yml defines backend, frontend, and container/browser jobs.

Local `.env` files, build output, and test artifacts are ignored.
Keep credentials out of version control. Demo accounts and content are optional sample data.

## Deployment scope

The supplied Compose configuration binds the frontend to localhost. Sessions and
login throttling are held in one backend process. Public hosting requires TLS,
trusted proxy configuration, credential and backup management, and deployment
hardening. Email verification, password recovery, MFA, shared sessions, and
distributed rate limiting are not implemented. Database persistence and forward
migrations are tested; backup recovery and rolling upgrades are not certified.
See [architecture decisions](docs/architecture.md) for constraints and tradeoffs.
