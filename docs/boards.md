# Public boards and administration

## Use the feature

Start the app with the commands in [frontend setup](frontend-setup.md). The home
page lists every board, including archived boards. Open a card to visit
/boards/{id}; direct links and refresh work. Boards support paginated questions, creation, and owner editing; see [questions](questions.md).
Empty boards show an invitation to ask the first question. Question threads support replies and accepted answers.

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

The sample accounts all initially use the configured DEMO_PASSWORD:

- alex.member@example.test — Alex River, MEMBER.
- sam.member@example.test — Sam Reed, MEMBER.
- morgan.moderator@example.test — Morgan Vale, MODERATOR.
- avery.admin@example.test — Avery Stone, ADMINISTRATOR.

Seed boards are **Getting started** (`getting-started`), **Product help**
(`product-help`), and **Using CommonBeacon** (`using-commonbeacon`). See the
[onboarding walkthrough](demo-walkthrough.md) for their nine questions and 45 visible answers.
The seeder runs in one transaction and inserts only missing emails/slugs.
Repeated startup does not duplicate them, reset passwords, replace roles, unarchive
boards, or overwrite edited metadata. Existing accounts with those addresses are
preserved as they are; the seeder never promotes an existing registered account.
Changing DEMO_PASSWORD later affects only accounts created after that change.

Keep your local `.env` and credentials out of version control.

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

## Verification and implementation notes

Run scripts/verify.ps1 for backend and frontend checks. Board integration tests
use real HTTP cookie sessions and disposable PostgreSQL containers. They verify
public access, permissions, CSRF, validation, duplicate slugs, archive/reopen,
concurrent edits, and repeat seeding. Separate tests verify the profile/flag gate
and invalid-password behavior.

For browser checks, run `npm run test:smoke` from frontend with Docker and Chrome
available. The runner builds its own isolated stack, tests administrator board
creation/edit/archive/reopen and anonymous reads, then removes the test database.
See [browser verification](browser-testing.md); the development stack is untouched.

Implementation responsibilities:

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

Archived boards reject ordinary question/reply creation, owner editing, and
accepted-answer changes; reporting and moderation remain available. Board listing remains unpaginated.

## Verification

See [verification](verification.md) for repeatable commands, coverage, recorded
results, and current limitations.
