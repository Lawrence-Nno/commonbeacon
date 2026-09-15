# CommonBeacon — Milestone A: Chronological Implementation Plan

## Objective

Deliver CommonBeacon, a working customer-support community using Java 21, Spring Boot, PostgreSQL, React with TypeScript, and Docker Compose.

The milestone is complete when two members can register and sign in, one asks a question, the other replies, and the question author accepts that reply. The result survives an application restart, unauthorized changes are rejected by the backend, and automated checks verify the workflow.

This document turns [the implementation guide](IMPLEMENTATION_GUIDE.md) into an ordered execution checklist. The guide remains the source for architectural and business rules. This plan defines when to implement them and how to prove each stage works.

**Current status:** planning complete; application implementation has not started. Every stage below is pending. Check a stage off only after its exit criteria have been demonstrated.

## Scope boundaries

Included:

- Local registration, session login/logout, and current-user information.
- Public boards, board administration, and archival behavior.
- Paginated questions and replies, owner editing, and accepted solutions.
- Member, moderator, and administrator roles enforced on the server.
- Database migrations, development demo data, tests, containers, and CI configuration.
- Usable loading, empty, validation, authorization, and failure states.

Milestone B will add reports, moderator queues, hide/restore operations, knowledge articles, and search. Milestone C will add GraphQL and AI drafting. In Milestone A, the moderator role exists and has member capabilities; its specialized operations arrive in Milestone B. The core community uses plain-text content.

Do not add search controls or moderation buttons that imply unavailable functionality. Store visibility fields now as specified in the guide, but defer moderation workflows and their race-condition tests until those operations exist.

## How we will execute each stage

1. Explain the intended change and expected behavior before editing.
2. Implement the smallest complete deliverable for the stage.
3. Add relevant tests alongside the behavior, rather than postponing all testing.
4. Run the stage's required checks and correct failures.
5. Review the diff and describe what changed, what passed, and any limitations.
6. Update the completion record with actual evidence before moving forward.

Stages run in the order below. A failed exit criterion is unfinished work. Keep each stage suitable for a focused review; a stage can contain several small commits if version control is available.

## Stage 1 — Confirm tools and establish the repository

**Status:** [ ] Pending

**Purpose:** establish a reproducible foundation before choosing exact dependency versions or generating applications.

### Work

1. Inspect the workspace and applicable repository instructions.
2. Check Java, Node/npm, Git, Docker, and Docker Compose availability and versions.
3. Confirm the Docker engine is reachable, not merely that its CLI is installed.
4. Choose a stable Spring Boot release compatible with Java 21 and mutually compatible frontend packages using official documentation at implementation time.
5. Record exact selected versions and required prerequisites in `README.md`.
6. Add `.gitignore` for generated build files, local environments, IDE files as appropriate, and test artifacts.
7. Establish the initial folder layout without creating placeholder modules for future milestones.
8. Check version-control status. If no repository exists, initialize local Git; remote publishing is not necessary for this milestone.

### Deliverables

- `README.md` with project purpose, scope, and prerequisites.
- `.gitignore`.
- Initial `backend/`, `frontend/`, `scripts/`, and `docs/` structure as needed by subsequent stages.
- A short record of selected tools and any missing prerequisites.

### Exit criteria

- Java 21 and a compatible Node version are available, or a concrete installation blocker is recorded.
- Docker can run containers before database-dependent stages begin.
- Versions are explicit; secrets and build outputs will not enter version control.

**Java learning checkpoint:** explain the difference between the JDK, JVM, Maven, and Spring Boot.

## Stage 2 — Bootstrap the backend and PostgreSQL

**Status:** [ ] Pending — depends on Stage 1

**Purpose:** prove that the Java application starts against a real relational database with controlled schema changes.

### Work

1. Generate the Maven-based Spring Boot application and commit the Maven Wrapper.
2. Add MVC, validation, JPA, PostgreSQL, Flyway, Actuator, and test dependencies using Boot dependency management where applicable.
3. Configure the local datasource through environment variables.
4. Add the PostgreSQL service to `compose.yaml`, with a pinned image, named volume, and healthcheck.
5. Add `.env.example` with local placeholders and explicit Compose variable mappings.
6. Add the first real Flyway migration for `app_user`: UUID ID, normalized unique email, display name, password hash, role, and creation timestamp.
7. Configure Hibernate schema validation and disable Open Session in View.
8. Add a minimal health endpoint and an integration test that starts PostgreSQL with Testcontainers and applies migrations.
9. Configure unit and integration test discovery so Maven `verify` executes both.

### Deliverables

- `backend/pom.xml`, wrapper files, application class, and configuration.
- `backend/src/main/resources/db/migration/V1__create_app_user.sql`.
- Initial Compose database service and `.env.example`.
- Startup/migration integration test.

### Exit criteria

- Maven `verify` passes and its output shows that the integration test ran.
- The backend starts against local PostgreSQL and reports health.
- Restarting the backend does not reapply or duplicate the migration.
- A database-container restart preserves a temporary test row in the local development database; remove that row afterwards.

**Java learning checkpoint:** explain dependency injection, application configuration, and why migrations own the schema.

## Stage 3 — Bootstrap the frontend and verification commands

**Status:** [ ] Pending — depends on Stage 2

**Purpose:** connect a real browser application to the Java backend and make routine checks repeatable.

### Work

1. Scaffold React with TypeScript and Vite; commit the lockfile.
2. Add routing, a page shell, basic design tokens, and accessible shared controls.
3. Configure a development proxy for `/api`; expose only minimal health information needed for the initial connectivity check.
4. Introduce a typed HTTP client and a consistent frontend error type.
5. Configure lint, TypeScript checking, Vitest, React Testing Library, and production build scripts.
6. Add `scripts/verify.ps1` to run backend verification and frontend checks, stopping on a nonzero native-command exit code.
7. Add an initial CI workflow with equivalent checks. Configure the backend job to support Testcontainers.
8. Document working directories and commands in the README.

### Exit criteria

- The browser can reach the backend through the development proxy.
- A backend outage produces a readable frontend failure state.
- Frontend lint, type checking, tests, and build pass.
- The verification script reports failure if a required command fails.
- CI configuration exists; record remote CI as unverified until actually executed on a remote host.

**Learning checkpoint:** trace a browser request through the proxy to the Java handler and back.

## Stage 4 — Implement registration and secure sessions

**Status:** [ ] Pending — depends on Stage 3

**Purpose:** establish trustworthy user identity before introducing content ownership.

### Backend work

1. Implement the user entity, repository, registration DTOs, and identity service.
2. Normalize emails consistently and enforce uniqueness in PostgreSQL.
3. Validate display names and passwords with explicit documented bounds; hash passwords through Spring Security.
4. Always assign `MEMBER` on public registration. Reject or ignore role fields by a documented request policy that cannot grant privileges.
5. Implement `/api/v1/auth/csrf`, `/register`, `/login`, `/logout`, and `/me` as defined in the guide.
6. Use Spring Security's session handling, including fixation protection and logout invalidation.
7. Implement CSRF protection for login and other state changes, including token renewal after authentication changes.
8. Add role/ownership support and consistent `ProblemDetail` responses, including security-filter failures.
9. Add bounded login attempts and generic invalid-credential errors.

### Frontend work

1. Implement registration and login screens with field errors.
2. Fetch current-user state on page load.
3. Add CSRF bootstrap and token headers to the HTTP client.
4. Add logout, session-expiry behavior, and user-specific cache clearing.
5. Preserve form input on recoverable errors, except sensitive fields where appropriate.

### Exit criteria

- Registration, login, reload while signed in, and logout work in the browser.
- A duplicate normalized email returns a controlled conflict; concurrent registration cannot create duplicates.
- Missing CSRF tokens reject protected mutations.
- Password hashes and other users' email addresses never appear in public responses.
- A browser cannot become an administrator by modifying registration input.
- Automated tests verify real session behavior, not just a mocked authenticated principal.

**Java learning checkpoint:** explain authentication versus authorization and where security filters run relative to controllers.

## Stage 5 — Add public boards and administrator controls

**Status:** [ ] Pending — depends on Stage 4

**Purpose:** provide a real place for questions and exercise role-based authorization.

### Work

1. Add the board migration, entity, repository, service, DTOs, and endpoints.
2. Support public board listing, administrator creation, metadata updates, and archival.
3. Enforce unique slugs and explicit field validation.
4. Add a development-only, idempotent seeder for two boards and fictional member, moderator, and administrator accounts.
5. Keep demo credentials configurable and ensure seeding cannot activate in the deployment profile by accident.
6. Build the community home and board page, initially with an honest empty-question state.
7. Add a small administrator board-management screen with create/edit/archive controls.

### Exit criteria

- Visitors can browse boards without authentication.
- Only administrators can create, edit, or archive boards through direct API requests.
- Archived boards remain visible and clearly labeled.
- Running the development seeder twice does not duplicate accounts or boards or reset existing passwords.
- Tests verify member and moderator requests cannot use administrator endpoints.

**Java learning checkpoint:** explain how a service applies role checks and translates database constraint failures into useful errors.

## Stage 6 — Implement question creation, reading, and editing

**Status:** [ ] Pending — depends on Stage 5

**Purpose:** deliver the first complete content workflow across the database, API, and UI.

### Work

1. Add the question table with board/author foreign keys, visibility, timestamps, and version.
2. Add the board-listing index and stable ordering by creation time plus ID.
3. Implement create, detail, paginated listing, and owner-edit services and endpoints.
4. Derive the author from the authenticated user.
5. Enforce trimmed title/body bounds and archive rules from the guide.
6. Return DTOs and consistent paginated response envelopes.
7. Implement `expectedVersion` checks and conflict responses for stale edits.
8. Build new-question form, board question list, question detail, and edit form.
9. Add loading, empty, missing-content, forbidden, and server-error states.

### Exit criteria

- A signed-in member can create and edit their own question and revisit it after refresh.
- Another member cannot edit it with a forged API request.
- Archived boards reject question creation and member editing.
- Pagination limits and invalid inputs are tested.
- Two edits based on the same version cannot silently overwrite each other.
- Public views exclude hidden questions, even though hiding is not yet exposed as a product action.

**Java learning checkpoint:** explain entities versus DTOs, transaction boundaries, and optimistic locking.

## Stage 7 — Implement replies

**Status:** [ ] Pending — depends on Stage 6

**Purpose:** complete the conversational part of the community.

### Work

1. Add the reply migration, including foreign keys, visibility, version, and listing index.
2. Implement paginated visible replies ordered oldest first with an ID tie-break.
3. Implement reply creation and owner editing with validation and version checks.
4. Reject replies and member edits on archived boards or inaccessible threads.
5. Build the reply list, composer, and owner-edit controls.
6. Invalidate relevant query caches after successful changes.
7. Disable duplicate submission while a request is pending and preserve draft text on recoverable failures.

### Exit criteria

- A second member can reply to the first member's question.
- Replies persist after reload and render in deterministic order.
- A member cannot edit someone else's reply or forge its author.
- Reply validation, stale edits, missing questions, and archive restrictions are tested.
- Public reply endpoints cannot expose a hidden parent question's thread.

**Java learning checkpoint:** explain foreign keys, relationship loading, and avoiding database queries during JSON serialization.

## Stage 8 — Implement accepted solutions and concurrency rules

**Status:** [ ] Pending — depends on Stage 7

**Purpose:** finish the defining Milestone A workflow with reliable ownership and consistency rules.

### Work

1. Add nullable `accepted_reply_id` to questions.
2. Add the reply composite unique constraint and question-to-reply composite foreign key described in the implementation guide.
3. Implement selecting, replacing, and clearing an accepted reply.
4. Require question ownership; administrators and moderators do not bypass this rule.
5. Verify question/reply visibility, board archival state, reply membership, and `expectedVersion` inside the transaction.
6. Acquire the question lock first, then any reply lock, using a consistent order.
7. Derive solved status from the accepted-reply reference.
8. Add the accepted-answer panel and select/clear controls; refresh question and board caches after success.
9. Add the solved/unanswered board filter and test its counts and pagination.

### Exit criteria

- A question author can accept, replace, and clear a solution; accepting their own reply is allowed.
- A reply from another question cannot be accepted, including through direct persistence attempts covered by integration tests.
- Other members, moderators, and administrators cannot select a solution for that author.
- Hidden replies and archived-board questions reject acceptance changes.
- Concurrent requests cannot produce multiple selections or silently bypass version checks.
- The solved indicator agrees with the stored accepted reply after reload.

The accept-versus-hide race test belongs to Milestone B, when the hiding operation is implemented. Keep the locking convention documented now so that future operation uses the same order.

**Java learning checkpoint:** explain why database constraints, locks, version checks, and authorization each solve different problems.

## Stage 9 — Package the complete application with Compose

**Status:** [ ] Pending — depends on Stage 8

**Purpose:** make the whole product reproducible without manually starting development servers.

### Work

1. Add a multi-stage backend Dockerfile and a frontend build/static-server Dockerfile.
2. Extend Compose with backend and frontend services.
3. Configure `/api` proxying and frontend deep-link fallback.
4. Use database health dependencies and a bounded backend readiness check.
5. Keep environment-specific database hostnames correct for host development and container execution.
6. Expose the frontend by default; keep optional database/debug ports localhost-bound.
7. Verify the PostgreSQL volume mount against the selected image's documented layout.
8. Expand README setup, startup, logs, shutdown, and troubleshooting instructions.

### Exit criteria

- Documented Compose commands build and start all three services.
- Opening a question URL directly and refreshing it works.
- Cookies and CSRF work through the proxy.
- Questions, replies, and accepted selections survive backend and database restarts.
- Ordinary shutdown preserves the named volume.
- Backend restart may sign users out because sessions are in memory; document and verify this expected limitation.

**Learning checkpoint:** explain container DNS, browser origins, database readiness, and persistent volumes.

## Stage 10 — Verify the complete user journey and polish failure states

**Status:** [ ] Pending — depends on Stage 9

**Purpose:** prove the product behaves correctly as a connected system.

### Work

1. Configure Playwright against the real backend and a disposable test database.
2. Use separate browser contexts for member A, member B, and administrator sessions.
3. Automate registration/login, question creation, reply, acceptance, reload, and logout.
4. Verify unauthorized edits, stale-update conflicts, archived-board restrictions, and session expiry.
5. Confirm user-specific caches clear when changing accounts.
6. Inspect narrow-screen layouts, keyboard navigation, focus visibility, long content, empty lists, and unavailable-backend behavior.
7. Add deterministic demo questions and replies to the development seeder.
8. Fix issues exposed by these checks and rerun affected verification.

### Exit criteria

- The end-to-end workflow passes with actual persistence and session authentication.
- Browser tests have isolated data and cannot clean up the development volume.
- Forms have accessible labels and actionable errors; failed requests do not misleadingly show success.
- Demo data is fictional, reproducible, and development-only.

**Learning checkpoint:** identify which failures require an integration or browser test rather than a mocked unit test.

## Stage 11 — Complete CI and Milestone A handoff

**Status:** [ ] Pending — depends on Stage 10

**Purpose:** make the result reviewable and reproducible for continued development and interview preparation.

### Work

1. Extend CI to build images, start an isolated stack, wait for readiness, and run browser tests.
2. Upload failure traces and logs, with cleanup that runs even after failure.
3. Finalize the verification scripts and exact local commands.
4. Document REST requests/responses, setup prerequisites, demo accounts, and known limitations.
5. Record short architectural decisions for sessions, one backend, migrations, and acceptance consistency.
6. Run the documented setup from a clean checkout or equivalent isolated copy.
7. Record actual verification output and a short demonstration script in `docs/evidence/milestone-a.md`.
8. Update this plan and the implementation guide's status text to reflect what now exists.

### Exit criteria

- Required backend, frontend, container, and browser checks pass.
- A fresh setup follows the README successfully.
- The code and docs contain no committed secrets or misleading claims of verification.
- If no remote repository is connected, distinguish locally verified checks from a remotely executed CI run.
- Every item in the completion checklist below has evidence.

## Milestone A completion checklist

- [ ] Register, sign in, reload authenticated state, and sign out.
- [ ] Browse public boards and paginated questions.
- [ ] Administer boards with server-enforced role checks.
- [ ] Create and edit questions as their owner.
- [ ] Create and edit replies as their owner.
- [ ] Accept, replace, and clear a solution as the question author.
- [ ] Reject cross-question acceptance, unauthorized edits, and stale updates.
- [ ] Enforce archival and public-visibility rules.
- [ ] Preserve application data across container restarts.
- [ ] Run meaningful PostgreSQL integration tests and a real-browser workflow.
- [ ] Run the full product through Docker Compose.
- [ ] Provide reproducible demo data, setup instructions, and verification commands.
- [ ] Provide CI configuration and accurately report whether remote CI has run.
- [ ] Explain the Java implementation and its important tradeoffs without relying on generated summaries.

## Completion record template

Append one record per completed stage. Leave future stages unchecked.

```text
Stage:
Date:
Implemented behavior:
Files changed:
Verification commands and results:
Manual/browser checks:
Known limitations or blockers:
Java concepts reviewed:
Next stage:
```

## Immediate next action

Execute Stage 1: inspect installed tools, confirm Docker availability, choose compatible exact versions, and establish the repository foundation. Then implement Stage 2 and verify a Java application connected to PostgreSQL before adding user-facing features.
