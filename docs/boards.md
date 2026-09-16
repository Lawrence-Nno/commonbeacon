# Stage 5: public boards and administration

> Stage 10 update: npm run test:smoke now creates and cleans up an isolated test stack. Earlier verification notes below describe the historical development-database runs. Follow [current browser testing instructions](browser-testing.md); no development credentials or running host backend are required.


## Use the feature

Start the app with the commands in [frontend setup](frontend-setup.md). The home
page lists every board, including archived boards. Open a card to visit
/boards/{id}; direct links and refresh work. Stage 6 adds paginated questions, creation, and owner editing; see [questions](questions.md).
Empty boards show an invitation to ask the first question. Replies are still planned.

Sign in as an administrator and choose **Manage boards** (/admin/boards).
Create a board with its name, slug, and description. Choose **Edit board** to change
metadata or toggle **Archived**. Clearing that checkbox reopens the board.
Archival preserves the board and its public URL. There is no delete action.

If someone else saves an edit first, the server returns 409. Your draft stays
in the form. **Reload latest and discard draft** explicitly replaces it with the
server's current data. Copy any draft text you want to keep before reloading.

## Local demo data

In the ignored root .env, set DEMO_SEED_ENABLED=true and DEMO_PASSWORD to your
chosen 12–128 character local password. Use unquoted literal values as required
by the existing environment helper. No password is included in .env.example.

Then, in the terminal used to start the backend:

~~~powershell
. .\scripts\use-dev-tools.ps1
. .\scripts\use-local-database.ps1
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run
~~~

The helper selects the local profile and loads demo settings for that terminal.
Seeding is disabled by default. It requires both the local profile and the enable
flag; it is disabled when prod is active, even with local also active.
Missing/invalid demo passwords fail startup before any seed data is written.

The fictional accounts all initially use the configured DEMO_PASSWORD:

- alex.member@example.test — Alex River, MEMBER.
- sam.member@example.test — Sam Reed, MEMBER.
- morgan.moderator@example.test — Morgan Vale, MODERATOR.
- avery.admin@example.test — Avery Stone, ADMINISTRATOR.

Seed boards are **Getting started** (getting-started) and **Product help** (product-help).
The seeder runs in one transaction and inserts only missing emails/slugs.
Repeated startup does not duplicate them, reset passwords, replace roles, unarchive
boards, or overwrite edited metadata. Existing accounts with those addresses are
preserved as they are; the seeder never promotes an existing registered account.
Changing DEMO_PASSWORD later affects only accounts created after that change.

This workspace has local demo seeding enabled and a generated password in .env.
That local value and all planning documents remain excluded from Git.

## REST contract

All routes use the /api/v1 prefix:

- GET /boards — public list, ordered by name then ID. Includes archived boards.
- GET /boards/{id} — public detail, including archived boards; 404 when missing.
- POST /boards — administrator only, JSON {slug, name, description}.
  Returns 201, a Location header, and the stored board.
- PATCH /boards/{id} — administrator only. Send expectedVersion and at least one
  of slug, name, description, or archived. Omitted/null metadata fields are left
  unchanged. Returns the updated board; archived must be a boolean.

Each response board contains id, slug, name, description, archived, createdAt,
and version. UUIDs identify stable routes; changing a slug does not break those URLs.
Both mutations require the current CSRF header and session cookie.

Input strings are trimmed. Slugs are 1–80 lowercase ASCII letters/digits with
single internal hyphens; names are 1–120 characters; descriptions are 1–2,000
characters. Names/descriptions must contain non-whitespace content and render as
plain text. expectedVersion is a nonnegative integer.

Malformed IDs/JSON and invalid fields return controlled 400 ProblemDetail bodies.
Duplicates and stale edits return 409; anonymous writes return 401 when the CSRF
token is valid; members/moderators receive 403. Missing CSRF returns 403 first.
The HTTP client now submits PATCH with a fresh CSRF token and understands board
ProblemDetail messages and field errors.

V2 creates the board table, unique slug constraint, validation constraints, and
version column. V1 remains unchanged. Spring Security checks endpoint permissions,
and BoardService repeats administrator enforcement with @PreAuthorize so another
caller cannot bypass authorization by avoiding the controller.

## Verification and Java learning

Run scripts/verify.ps1 for backend and frontend checks. Board integration tests
use real HTTP cookie sessions and disposable PostgreSQL containers. They verify
public access, permissions, CSRF, validation, duplicate slugs, archive/reopen,
concurrent edits, and repeat seeding. Separate tests verify the profile/flag gate
and invalid-password behavior.

For the Chrome checks, start the backend with demo seeding, then use:

~~~powershell
. .\scripts\use-dev-tools.ps1
. .\scripts\use-local-database.ps1
Set-Location frontend
npm.cmd run test:smoke
~~~

The browser test logs in with the seeded administrator, creates a uniquely named
fictional board, edits/archives/reopens it, and checks it from a separate anonymous
browser context. Smoke tests leave their fictional account/board rows in the
configured development database; they do not delete application data.
Use a disposable database for repeated runs. Full isolated workflows belong to Stage 10.

Java concepts to trace:

- A controller validates a request record and calls a Spring-managed service.
- @PreAuthorize checks the authenticated user's role before the service runs.
- @Transactional makes each mutation atomic; saveAndFlush exposes constraint
  failures before the response is produced.
- The unique database constraint resolves duplicate slugs even when requests race.
- @Version adds the version to the UPDATE condition. expectedVersion detects an
  already-stale form; Hibernate's version check also catches simultaneous saves.
- The exception handler translates those failures into useful HTTP responses.
- The demo seeder uses PostgreSQL ON CONFLICT DO NOTHING to preserve existing rows
  while avoiding changes to the public registration rule that always grants MEMBER.

Stage 6 enforces archive restrictions for question creation and owner editing.
Reply restrictions arrive with replies. Board listing remains unpaginated.

## Verified results — 2026-09-16

scripts/verify.ps1 completed with exit 0: 5 backend unit tests, 24 integration tests,
27 frontend tests, lint, TypeScript checking, and production build passed.
All 5 Chrome tests passed. Administrator desktop and archived-board mobile
screenshots were inspected; the mobile overflow check passed.

Stage 5 was committed and pushed as 4fa6d34. These are its local verification results.
