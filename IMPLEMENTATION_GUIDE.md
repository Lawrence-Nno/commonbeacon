# CommonBeacon: Implementation Guide

## 1. Purpose and success criteria

Build CommonBeacon, an independent customer-support community, to develop Java engineering skills and demonstrate end-to-end delivery with AI-assisted development. CommonBeacon uses original branding and an independently designed implementation.

The first complete demonstration must let a user sign in, ask a question, receive a reply from another user, and accept that reply as the solution. Data must survive application restarts. The server must enforce permissions, and automated tests must cover the workflow and its failure cases.

The extended demonstration adds moderation, a searchable knowledge base, GraphQL, and an AI answer draft with citations and human review.

Treat this guide as the target design. No application, test suite, or deployment exists yet. Commands below become usable as their corresponding files and scripts are implemented.

## 2. Scope and priorities

### Milestone A: Core community

- Local accounts, sign-in, sign-out, and current-user endpoint.
- Public boards and paginated question lists.
- Question creation and editing, replies, accepted solutions.
- Member, moderator, and administrator permissions.
- Database migrations, realistic demo data, container startup, and CI checks.

### Milestone B: Community operations

- Reports and a moderator review queue.
- Hide and restore content with recorded reasons.
- Knowledge-base drafts and publication.
- Search across visible questions and published articles.
- A small operational dashboard: unanswered questions, open reports, published articles.

### Milestone C: Interview extensions

- GraphQL reads backed by existing application services.
- One GraphQL mutation to demonstrate shared business rules.
- AI answer drafting from published knowledge articles.
- Reusable agent task specifications, verification scripts, and recorded evidence.

Defer private communities, multi-tenancy, social-network integrations, file uploads, chat, email delivery, reputation systems, Kubernetes, and microservices. One deployment represents one fictional company's community. Multi-tenancy is a future architectural change, not a capability implied by adding a tenant column.

## 3. Technology baseline

- **Java 21:** a deliberate learning baseline supporting records, modern switch expressions, and established tooling.
- **Spring Boot:** choose a supported stable release compatible with Java 21 at project initialization. Use Spring Initializr and retain its dependency management. Record the exact version in `backend/pom.xml`; do not select snapshot dependencies.
- **Maven Wrapper:** commit wrapper files so local development and CI use the same Maven version.
- **Spring MVC:** blocking HTTP request handling, appropriate for a first JPA application.
- **Spring Data JPA / Hibernate:** relational persistence through repositories.
- **Spring Security:** session authentication, CSRF protection, and authorization.
- **Bean Validation:** request constraints and field errors.
- **Flyway:** ordered SQL migrations; Hibernate validates the schema rather than modifying it.
- **PostgreSQL 18:** pin a tested patch image when scaffolding; use the same major version in integration tests.
- **React + TypeScript:** Vite application, React Router, and TanStack Query for server state.
- **CSS:** CSS modules and a small set of shared design tokens are sufficient initially.
- **Docker Compose:** database, backend, and frontend/reverse-proxy services.
- **Tests:** JUnit, Spring Boot Test, Spring Security Test, Testcontainers PostgreSQL, Vitest, React Testing Library, and Playwright.
- **Later:** Spring for GraphQL and a provider-neutral Java AI client adapter.

Commit `package-lock.json`, select a Node LTS version supported by the chosen Vite release, and record it in the README and CI. Pin container tags and dependency versions through manifests rather than relying on floating `latest` images.

Spring Boot's supported Java range depends on its release; check the [official system requirements](https://docs.spring.io/spring-boot/system-requirements.html) when scaffolding. React documents [Vite-based application setup](https://react.dev/learn/build-a-react-app-from-scratch) and [TypeScript usage](https://react.dev/learn/typescript).

## 4. Architecture

Use one Java application organized into feature modules. Each module contains its HTTP adapter, application services, persistence code, and request/response types.

Request flow:

```text
Browser / React
  -> same-origin /api requests
  -> Spring Security
  -> REST controller
  -> application service (permissions, business rules, transactions)
  -> repository
  -> PostgreSQL

Later: /graphql -> GraphQL controller -> the same application services
```

Controllers translate HTTP input and output. Services decide whether an action is allowed and coordinate changes. Repositories read and write data. Return explicit DTOs, never JPA entities, from public interfaces.

Keep transactions in application services. Disable Open Session in View so serialization cannot silently trigger database queries. Load needed relationships inside the service and map them to response DTOs.

Start with ordinary Java classes, interfaces where there is a meaningful boundary, records for DTOs, and enums for finite states. Avoid generic base services and repositories that obscure the feature being implemented.

### Proposed repository layout

```text
backend/
  pom.xml
  mvnw
  mvnw.cmd
  src/main/java/com/lawrencenno/commonbeacon/
    CommunityApplication.java
    identity/
    board/
    question/
    moderation/
    knowledge/
    search/
    assistance/                 # later
    shared/                    # errors, clock, configuration
  src/main/resources/
    application.yml
    db/migration/
    graphql/                   # later
  src/test/java/com/lawrencenno/commonbeacon/
frontend/
  src/
    app/
    features/
    components/
    lib/
  package.json
  package-lock.json
  e2e/
infra/
  nginx.conf
scripts/
  verify.ps1
docs/
  decisions/
  tasks/
  evidence/
compose.yaml
.env.example
.github/workflows/ci.yml
README.md
IMPLEMENTATION_GUIDE.md
```

Only create module directories when implementing them. Keep shared code small; business rules belong to their feature.

## 5. Product rules and permissions

### Actors

- **Visitor:** read visible community content and search published content.
- **Member:** visitor capabilities plus create questions, reply, edit their own visible content, and report content.
- **Moderator:** member capabilities plus inspect reports and hide or restore content with a reason.
- **Administrator:** moderator capabilities plus create/archive boards and manage knowledge articles.

Only the question author can select or clear an accepted solution. Moderators resolve abuse through moderation actions, not by silently rewriting the author's accepted answer. Role checks must run on the backend; hiding a button is only a usability choice.

### Core invariants

1. Every question belongs to an existing board and has an authenticated author.
2. Archived boards remain readable but reject new questions, replies, and member edits.
3. Question titles are trimmed, 5–200 characters; bodies are 10–20,000 characters; reply bodies are 1–20,000 characters after trimming.
4. A reply belongs to exactly one question. A question cannot accept a reply from another question.
5. A question has at most one accepted reply. The selected reply must be visible and belong to a visible question.
6. Question authors may accept their own replies; make this an explicit, tested product decision.
7. Hiding an accepted reply clears the selection in the same transaction. Restoring it does not automatically reaccept it.
8. Hiding a question hides its entire thread from public APIs and search. Preserve its reply states and accepted selection internally; restoring the question reveals only otherwise-visible replies.
9. Archived boards block acceptance changes by members; moderators can still perform safety actions.
10. Public responses never expose password hashes, email addresses of other members, reports, or private moderator notes.

Use `VISIBLE` and `HIDDEN` for content visibility. Derive whether a question is solved from its accepted-reply reference instead of maintaining a separate solved boolean.

## 6. Database design

Use UUID primary keys, `timestamptz` timestamps, Java `Instant`, and explicit foreign keys. Store enumerated values as readable strings with database checks. Use UTC for persistence and format times in the browser.

### Initial tables

**app_user**

- `id`, `email`, `display_name`, `password_hash`, `role`, `created_at`.
- Normalize email using one documented policy before insertion; enforce uniqueness with a database index on the normalized value.
- Public registration always creates a member. Provision local demo administrators through a development-only seeder.

**board**

- `id`, `slug`, `name`, `description`, `archived`, `created_at`.
- Unique slug; archived boards retain existing content.

**question**

- `id`, `board_id`, `author_id`, `title`, `body`, `visibility`.
- Nullable `accepted_reply_id`; `created_at`, `updated_at`, `version`.
- Index `(board_id, created_at DESC, id DESC)` for stable board listings.

**reply**

- `id`, `question_id`, `author_id`, `body`, `visibility`, timestamps, `version`.
- Index `(question_id, created_at, id)`.
- Add a unique constraint on `(question_id, id)` and a composite foreign key from `question(id, accepted_reply_id)` to `reply(question_id, id)` after both tables exist. This prevents cross-question acceptance at the database boundary.
- Use soft hiding rather than hard deletion in the initial product, avoiding circular deletion behavior.

### Later tables

**content_report**

- `id`, `reporter_id`, nullable `question_id`, nullable `reply_id`, `reason`, `status`, timestamps, resolver and resolution note.
- Check that exactly one target is populated; use real foreign keys for both targets.
- `status`: `OPEN` or `RESOLVED`. Use partial unique indexes to allow only one open report per reporter per target.
- A repeated open report returns a conflict response. Other users can independently report the same target.

**moderation_action**

- `id`, `actor_id`, nullable question/reply target, `action`, `reason`, `created_at`.
- Exactly one target; append-only through application behavior. This is an application audit trail, not a tamper-proof compliance system.

**knowledge_article**

- `id`, `slug`, `title`, `body`, `status`, `author_id`, timestamps, `published_at`, `version`.
- `status`: `DRAFT`, `PUBLISHED`, `ARCHIVED`. Only published articles appear publicly.

**ai_draft** (extension)

- `id`, `question_id`, `requested_by`, `status`, `draft_body`, `model`, `prompt_version`, timestamps, failure code, nullable `published_reply_id`.
- Store source article IDs and content versions in a related `ai_draft_source` table.
- States: `PENDING`, `RUNNING`, `READY`, `FAILED`, `PUBLISHED`; prevent duplicate publication.

### Migrations and concurrency

- Begin with identity and board migrations, then questions/replies, then moderation and knowledge migrations.
- Never modify an applied migration. Add a new migration for every schema change.
- Set `spring.jpa.hibernate.ddl-auto=validate` and `spring.jpa.open-in-view=false`.
- Put `@Version` on editable entities. Updates carry `expectedVersion`; mismatches return `409 Conflict`.
- Acceptance and reply-hiding operations acquire the question row lock first, then any reply lock. Use the same lock order everywhere to reduce deadlocks.
- Recheck permissions and visibility inside the transaction after obtaining locks.
- Add a two-transaction integration test for accepting a reply while it is being hidden: no committed state may reference a hidden accepted reply.

## 7. Authentication and security

Choose server-side sessions for this same-origin browser app. An in-memory session store is enough for one backend instance; document that restarting it signs users out. Persistent sessions are a later enhancement.

Implement:

1. Password hashing through Spring Security's supported password encoder; never store raw passwords.
2. Session cookie marked HttpOnly and SameSite=Lax; Secure in an HTTPS environment. Local HTTP uses a clearly isolated development profile.
3. CSRF protection for state-changing requests, including login and logout. Expose a CSRF bootstrap endpoint and have the React HTTP client submit the token header.
4. Refresh the CSRF token after login/logout according to the selected Spring Security version's SPA guidance.
5. Session fixation protection and session invalidation on logout using Spring Security's authentication/session facilities.
6. Generic invalid-credentials responses and bounded login attempts; a single-instance in-memory limiter is acceptable if documented and tested.
7. Service-level ownership checks for all edits, acceptance, and moderation.
8. Request size limits, bounded pagination, and allowlisted sort options.

Prefer Spring Security's authentication filter flow. If implementing a custom JSON login adapter, ensure authentication is saved to the security-context repository and session authentication strategies run; merely setting a thread-local security context is insufficient.

Use plain text bodies initially. If Markdown is added, disable raw HTML and sanitize rendered output. Never display untrusted content through unrestricted HTML injection.

The Vite development proxy and production reverse proxy keep `/api` on the frontend origin. Do not introduce permissive credentialed CORS as a shortcut.

Reference: [Spring Security CSRF documentation](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

## 8. REST API contract

Prefix endpoints with `/api/v1`. Use UUID strings externally. Derive the actor from authentication, never from an `authorId` submitted by the browser.

### Identity

- `GET /auth/csrf`: fetch token and header name; response must not be cached.
- `POST /auth/register`: email, display name, password; returns `201`, without automatic login initially.
- `POST /auth/login`: credentials; returns current-user summary and session cookie.
- `POST /auth/logout`: invalidate session; returns `204`.
- `GET /auth/me`: authenticated user's ID, display name, and role; returns `401` otherwise.

Choose and document the login content type during scaffolding so the frontend matches the security filter.

### Boards and questions

- `GET /boards`: public board list.
- `POST /boards`: administrator-only board creation.
- `PATCH /boards/{id}`: administrator-only metadata or archive change.
- `GET /boards/{id}/questions?page=0&size=20`: visible question summaries.
- `POST /boards/{id}/questions`: create question; returns `201` and Location header.
- `GET /questions/{id}`: question details and accepted-reply summary; replies load separately.
- `PATCH /questions/{id}`: owner edits title/body with `expectedVersion`.
- `GET /questions/{id}/replies?page=0&size=20`: visible replies, oldest first.
- `POST /questions/{id}/replies`: create reply.
- `PATCH /replies/{id}`: owner edit with `expectedVersion`.
- `PUT /questions/{id}/accepted-reply`: `{ "replyId": "...", "expectedVersion": 2 }`.
- `DELETE /questions/{id}/accepted-reply?expectedVersion=3`: clear selection.

### Operations and knowledge

- `POST /reports`: exactly one question/reply ID and a reason.
- `GET /moderation/reports?status=OPEN`: moderator-only queue.
- `GET /moderation/reports/{id}`: privileged target context, including hidden content.
- `POST /moderation/reports/{id}/resolve`: dismiss, hide, or acknowledge already-hidden content; record resolution and any moderation action atomically.
- `POST /moderation/questions/{id}/restore` and `/moderation/replies/{id}/restore`: reason required.
- `GET /articles` and `GET /articles/{slug}`: published content only.
- `GET /admin/articles` and `GET /admin/articles/{id}`: administrator draft management.
- `POST /admin/articles`, `PATCH /admin/articles/{id}`, `POST /admin/articles/{id}/publish`, `POST /admin/articles/{id}/archive`.
- `GET /search?q=...&page=0&size=20`: public visible questions and published articles.
- `GET /moderation/summary`: operational counts, with role checks.

### Consistent responses

Default page size is 20, maximum 100; reject negative page numbers and invalid sizes. Sort newest questions by creation timestamp and ID for a stable tie-break. Offset pagination is adequate initially; concurrent inserts can shift page boundaries, which should be documented.

List envelope:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

Use Spring `ProblemDetail` with a stable application error code, optional field errors, and a request ID. Never expose SQL messages or stack traces.

- `400`: malformed or invalid input.
- `401`: unauthenticated protected request.
- `403`: authenticated actor lacks permission; also handle CSRF failures consistently.
- `404`: absent or publicly inaccessible content.
- `409`: stale version, duplicate unique value, or incompatible state.
- `429`: throttled request.

Add an OpenAPI contract after the core endpoints stabilize. Keep request examples in the repository and verify they match implemented DTOs.

## 9. Frontend implementation

### Screens

1. Community home: board cards, recent questions, search entry.
2. Board: paginated questions, solved/unanswered filter, new-question action.
3. Question: body, author, timestamp, accepted answer, reply list and composer.
4. Sign-in/register: field validation and actionable errors.
5. Moderator queue: report details, content context, decision with reason.
6. Knowledge base: article list, article view, administrator editor.
7. Later: answer-draft panel showing sources and explicit review/publish controls.

Suggested routes: `/`, `/boards/:boardId`, `/questions/:questionId`, `/login`, `/register`, `/moderation`, `/knowledge`, `/knowledge/:slug`, `/admin/articles`.

### Implementation rules

- Build a typed HTTP client responsible for credentials, CSRF headers, JSON decoding, and problem responses.
- Use TanStack Query for fetched state and cache invalidation; keep form state local.
- After a reply, invalidate the thread's reply list. After acceptance, invalidate question details and board summaries.
- Clear user-specific caches on logout and account switch, especially moderation and draft data.
- Prefer server-confirmed updates for moderation and accepted solutions; avoid optimistic UI until conflict behavior is understood.
- Handle loading, empty, forbidden, missing, stale-edit, and network-error states explicitly.
- Preserve typed content after a recoverable error. A stale edit should prompt reload/reconciliation rather than overwrite another change.
- Disable repeated form submission while a request is pending. Disablement alone does not replace backend constraints.
- Use semantic forms, labels, keyboard navigation, visible focus, and accessible error messages.
- Test narrow screens and long titles, usernames, and unbroken text.

Use original branding and fictional data. A polished, coherent support workflow is the visual target.

## 10. Search and knowledge base

Start with a bounded database query over visible question titles and published article titles. Introduce PostgreSQL full-text search in Milestone B when the baseline workflow is stable.

For full-text search, add weighted `tsvector` values for titles and bodies and GIN indexes through Flyway. Specify the text-search language explicitly and use parameterized queries. Merge question and article hits into a typed result containing content kind, title, snippet, URL, and rank. Sort with a deterministic tie-break.

Apply visibility/publication filters before returning results or counts. Do not leak hidden text through snippets. Test title ranking, punctuation, empty input, no matches, hidden questions, and draft articles.

PostgreSQL provides the necessary parsing, ranking, and indexing primitives; a separate search server is unnecessary for this scope. See [PostgreSQL full-text search](https://www.postgresql.org/docs/current/textsearch-intro.html).

## 11. Containers and local development

### Services

- `db`: PostgreSQL, named persistent volume, `pg_isready` healthcheck.
- `backend`: multi-stage Maven/JDK build and Java runtime image; waits for a healthy database.
- `frontend`: Node build stage followed by a static web server serving the React build and proxying `/api` to `backend:8080`.
- Later, proxy `/graphql` through the same origin.

Use the service hostname `db` inside the backend container; use `localhost` when running the backend directly on the host. In a browser, `backend` is not a resolvable Compose service name: browser requests use relative URLs through the proxy.

Configure the frontend server to return `index.html` for application routes, while preserving API errors. Check that refreshing `/questions/:id` loads correctly.

Expose only the frontend in the default complete stack. A development override may bind the database to `127.0.0.1:5432` for host tools and the backend to localhost for debugging.

Healthcheck dependencies should use `condition: service_healthy`; startup ordering alone does not indicate database readiness. The backend still needs sensible connection timeouts and recovery after a database interruption. See [Docker Compose startup order](https://docs.docker.com/compose/how-tos/startup-order/).

### Configuration contract

Document these variables in `.env.example` with nonsecret placeholders:

```dotenv
POSTGRES_DB=community
POSTGRES_USER=community
POSTGRES_PASSWORD=replace-for-local-development
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/community
SPRING_DATASOURCE_USERNAME=community
SPRING_DATASOURCE_PASSWORD=replace-for-local-development
SPRING_PROFILES_ACTIVE=local
AI_ENABLED=false
```

Explicitly map variables into services in Compose; a `.env` file is not automatically injected into every container. Keep the two database password settings consistent, or derive the backend password from `POSTGRES_PASSWORD` in Compose. Ignore `.env` in git. Never put AI credentials in frontend variables or committed files.

Mount the database volume at the location required by the chosen PostgreSQL image, checking that image's documentation during scaffolding. Verify persistence rather than assuming the mount path is correct.

### Expected commands once scaffolded

From repository root in PowerShell:

```powershell
Copy-Item .env.example .env
docker compose config
docker compose up --build -d
docker compose ps
docker compose logs backend
docker compose down
```

`docker compose down` should preserve the named database volume. Do not make volume deletion part of routine startup or verification.

For fast host development, run PostgreSQL through Compose, launch `backend/mvnw.cmd spring-boot:run` from the backend directory with host database settings, and run `npm run dev` from the frontend directory. Configure the Vite proxy to target the host backend.

## 12. Testing and quality gates

### Backend

- Unit tests: ownership decisions, acceptance rules, state transitions, and validation helpers.
- PostgreSQL integration tests: Flyway migrations, unique constraints, pagination, persistence, and locking behavior.
- HTTP tests: real security configuration, login/session flow, CSRF, unauthenticated requests, role checks, and error mapping.
- Use Testcontainers PostgreSQL rather than H2 for database-specific behavior. [Official PostgreSQL module documentation](https://java.testcontainers.org/modules/databases/postgres/).
- Configure Maven Surefire for unit tests and Failsafe for `*IT` integration tests so `verify` runs both; confirm test counts in CI.

High-value cases:

1. A member cannot edit another member's question through a crafted request.
2. A reply from question B cannot be accepted on question A.
3. A hidden accepted reply is cleared atomically.
4. Stale updates return conflict and preserve newer content.
5. Concurrent duplicate registrations leave one account.
6. Missing CSRF tokens reject mutations even with a valid session.
7. Hidden content is absent from direct public reads, lists, search results, and snippets.
8. Failed moderation transactions leave neither a partial state change nor a misleading audit entry.

### Frontend and browser

- Component tests for form errors, permission-dependent controls, and failure states.
- Playwright runs against the real backend and a disposable test database.
- Main browser test: member A creates a question; member B replies; member A accepts it; refresh and verify persistence.
- Moderator test: report an accepted reply, hide it, and verify it disappears and the question becomes unanswered.
- Use separate browser contexts for different actors to avoid accidental shared sessions.
- Include keyboard navigation and a narrow-screen smoke check.

### Verification entry point

Implement `scripts/verify.ps1` to run backend `verify`, frontend lint, type checking, tests, and production build. Explicitly check `$LASTEXITCODE` after native commands; `$ErrorActionPreference` alone does not reliably handle their nonzero exits. Exit nonzero on the first failed required check.

Provide a separate documented command/profile for browser tests with disposable data. Never point test cleanup at the development volume.

Define frontend scripts such as `lint`, `typecheck`, `test:run`, `build`, and `test:e2e` in `package.json` before documenting them as runnable.

## 13. CI, operation, and release evidence

Use GitHub Actions when a GitHub repository exists. The same checks should remain runnable locally.

Pipeline stages:

1. Check out code; set up pinned Java and Node versions.
2. Run Maven Wrapper `verify` on a runner with Docker available to Testcontainers.
3. Run `npm ci`, lint, type checking, component tests, and build.
4. Build application images and start an isolated Compose stack with test data.
5. Wait for bounded readiness checks, then run Playwright.
6. Upload failed browser traces and service logs; clean up the isolated test stack.

Add backend health/readiness endpoints through Actuator, expose minimal health information, and protect detailed operational endpoints. Include request IDs and useful structured logs, excluding credentials and sensitive bodies.

Before marking a release ready:

- Start from a clean checkout using documented commands.
- Verify database persistence across restart.
- Confirm no development administrator credentials or seed behavior are active in a deployment profile.
- Document version, known limitations, verification results, and demo steps.
- Test a PostgreSQL backup/restore into a separate disposable database when deployment is added.

No public hosting is necessary for the first milestone. If hosting later, add HTTPS, external secret configuration, backups, resource limits, and a migration/rollback plan. An older application image may not support a newer schema; rollback requires explicit compatibility planning.

## 14. GraphQL phase: add after REST works

Entry gate: the core workflow passes backend and browser tests through REST, permissions live in services, and the containerized application runs reliably.

Add Spring for GraphQL to the existing backend. Begin with read-only question details and paginated replies. Do not replace login, logout, health endpoints, or every existing REST route.

Example initial schema:

```graphql
type Query {
  question(id: ID!): Question
}

type Question {
  id: ID!
  title: String!
  body: String!
  author: PublicUser!
  acceptedReply: Reply
  replies(page: Int = 0, size: Int = 20): ReplyPage!
}

type PublicUser {
  id: ID!
  displayName: String!
}

type Reply {
  id: ID!
  body: String!
  author: PublicUser!
}

type ReplyPage {
  items: [Reply!]!
  totalElements: Int!
}
```

Resolvers call existing application/query services. Use request-scoped batching for author lookups to avoid one database query per reply. Spring supports annotated controllers and batch mappings; see [Spring GraphQL controller documentation](https://docs.spring.io/spring-graphql/reference/controllers.html).

Apply page bounds, query depth/complexity limits, and timeouts. Never cache authorization-sensitive data across users. Preserve CSRF protection for session-authenticated GraphQL POST requests. Inspect GraphQL errors in the client even when HTTP status is 200.

Next add `acceptReply(questionId, replyId, expectedVersion)` as a mutation using the existing acceptance service. Add parity tests proving REST and GraphQL reject the same forbidden and invalid operations.

Move only the question detail screen to GraphQL initially. Measure query counts before and after batching and record the result. GraphQL is useful here as an alternative interface, not as a reason to duplicate domain logic.

## 15. AI answer drafting phase

Entry gate: published knowledge articles, search, permissions, moderation, and core verification are stable.

Workflow:

1. A moderator requests a draft for a visible question.
2. The backend retrieves a bounded set of published articles relevant to that question.
3. It sends only necessary question text and source excerpts to a configured model provider.
4. It stores the draft with source IDs, versions, model identifier, and prompt version.
5. The moderator reviews and edits the draft before explicitly publishing a normal reply.

Define an `AnswerDraftProvider` interface and deterministic fake implementation. Default local development and CI use the fake; a real provider requires explicit configuration.

Implement a small database-backed job queue in the same application for draft generation. Return `202 Accepted` with a draft/job ID, poll status, and keep network calls outside database transactions. Define a lease/timeout for RUNNING jobs so a process crash does not strand them forever. Keep worker concurrency and retries bounded.

Controls:

- Treat source text as untrusted data; it cannot grant tool permissions or change system instructions.
- Give the model no database-write or publishing tools.
- Return an insufficient-evidence result when retrieval finds no suitable sources.
- Check generated source references against the supplied article IDs; valid IDs alone do not prove factual accuracy.
- Recheck article visibility/version and question visibility before publishing. Require regeneration or further review if sources changed.
- Publish the reply and mark the draft PUBLISHED atomically, using a unique draft-to-reply association to make repeated publication safe.
- Limit source count, input size, output size, timeout, and provider retries; record duration and usage without logging secret keys or unnecessary personal data.

Create a small evaluation set with answerable questions, insufficient evidence, contradictory articles, malicious instructions in sources, and provider failures. Review support for claims and citation correctness. Distinguish deterministic integration tests from human evaluation of answer quality.

## 16. Phased implementation checklist

### Phase 0: Foundation

- Scaffold backend and frontend; record exact versions and prerequisites.
- Add wrapper, lockfile, database container, initial migration, health route, and frontend shell.
- Add `.gitignore`, `.env.example`, README, and basic CI checks.
- Verify backend connects to PostgreSQL and frontend reaches the health route through its proxy.

Java focus: packages, dependency injection, configuration, Maven lifecycle, and application startup.

### Phase 1: Identity and boards

- Implement user persistence, hashing, registration, session login/logout, CSRF, and board reads.
- Add development-only demo users with different roles and two boards.
- Implement login/register forms and current-user state.
- Verify unauthorized writes and role escalation attempts fail.

Java focus: records, validation annotations, interfaces, exceptions, and security filters.

### Phase 2: First complete vertical feature

- Implement question/reply tables and service methods, then REST endpoints and screens.
- Implement accepted solutions, version checks, and database constraints.
- Add the main multi-user browser test and concurrency integration test.
- Package the complete application with Compose and verify persistence.

Exit gate: demonstrate ask, reply, accept, restart, and revisit without manual database edits.

Java focus: entity relationships, transaction boundaries, collections, DTO mapping, and locking.

### Phase 3: Moderation and usability

- Add reports, resolution transactions, hide/restore, and audit records.
- Add moderator screens and all required failure/empty/loading states.
- Verify hidden content disappears everywhere public and accepted-answer rules remain consistent.

Java focus: state transitions, authorization reuse, atomic operations, and integration tests.

### Phase 4: Knowledge and search

- Add article lifecycle, administrator editor, public knowledge pages, and full-text search.
- Add operational counts and query indexes informed by actual query plans.
- Test publication visibility, snippets, search ranking, and pagination.

Java focus: query projections, parameterized SQL, performance measurement, and data boundaries.

### Phase 5: GraphQL

- Add schema, question query, batching, bounds, and error mapping.
- Move one frontend screen and add acceptance mutation parity tests.
- Record query counts and explain the API tradeoff.

Java focus: alternate adapters, batching, shared services, and API contracts.

### Phase 6: AI draft and interview preparation

- Add provider abstraction, fake, background jobs, source tracking, and reviewed publication.
- Run the evaluation set and record limitations.
- Rehearse the demonstration and prepare architecture decisions and verified results.

Java focus: HTTP clients, timeouts, background execution, idempotency, and failure recovery.

Advance by exit criteria rather than fixed dates. Finish Phase 2 before investing in later features; it is already a substantial learning milestone.

## 17. AI-assisted engineering workflow

For each feature, create a task specification containing:

- User outcome and explicit scope.
- Request/response contract and business invariants.
- Files or module boundaries involved.
- Positive and negative acceptance cases.
- Required verification commands and observable browser behavior.
- Completion evidence and known limitations.

Use the loop: specify -> implement -> run checks -> inspect failures -> correct -> verify the actual workflow -> review the diff.

Reusable repository instructions should explain architecture boundaries, permission rules, migration conventions, and verification commands. Add focused scripts only when they automate repeatable work. Avoid producing instructions that merely repeat obvious language syntax.

When parallel agent work is explicitly authorized, assign independent tasks with clear ownership, such as frontend work against an agreed contract and backend work implementing it. Keep shared schema changes owned by one task. Integrate and verify the whole feature after the individual tasks finish.

Your learning checkpoint after every phase:

1. Explain the request path from browser to database without reading a generated summary.
2. Explain each important Java annotation and the behavior it enables.
3. Predict what happens when validation fails or a transaction rolls back.
4. Read the generated SQL for one representative query.
5. Explain a rejected implementation and why the selected design fits this scope.

Record actual commands, results, and measurements. Do not claim production experience, speed improvements, or passing tests that this project has not demonstrated.

## 18. Interview demonstration and completion definition

Prepare a short demo using fictional accounts and seeded data:

1. Sign in as a member and ask a question.
2. Reply from another member's browser context.
3. Accept the reply and show the persisted solution.
4. Demonstrate a blocked unauthorized change.
5. Report and hide the accepted reply as a moderator; show the audit entry and cleared solution.
6. If completed, show cited AI drafting and explicit publication.
7. Show a meaningful test and the verification workflow that caught a real issue.

Keep short architecture decision records for sessions, a single backend, PostgreSQL search, acceptance concurrency, and delayed GraphQL. Each record states the problem, decision, alternatives, and consequences.

A feature is complete when its acceptance criteria work, meaningful automated checks pass, browser behavior is inspected, documentation matches the implementation, and remaining limitations are stated. A portfolio release additionally needs reproducible startup and a clean demo dataset.

Be able to discuss what you built, what you learned, and what would change for multiple application instances, private communities, higher traffic, and public deployment. Present those as future design considerations unless implemented and verified.

## 19. Project identity

- Product name: **CommonBeacon**.
- Repository name: `commonbeacon`.
- Java base package: `com.lawrencenno.commonbeacon`.
- Description: A customer-support community built with Java, Spring Boot, PostgreSQL, and React.

Use this identity consistently in application branding, package names, documentation, and deployment metadata. Product scope is defined by this guide and the milestone plan.
