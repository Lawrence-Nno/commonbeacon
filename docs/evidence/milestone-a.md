# Milestone A evidence and demonstration

## Status

Stages 1–11 are implemented and locally verified. Stage 11 was committed/pushed as
2d33c96de62b6feda06a4b32366e300844f7ff4a and passed all three remote CI jobs.

The existing backend/frontend workflow passed remotely for Stage 10:
[GitHub run 35097348094](https://github.com/Lawrence-Nno/commonbeacon/actions/runs/35097348094).
On 2026-09-19, inspected [Stage 11 run 35100360402](https://github.com/Lawrence-Nno/commonbeacon/actions/runs/35100360402)
for the exact Stage 11 commit. Backend/PostgreSQL, frontend quality, and
container/browser jobs all completed successfully, including artifact upload and
isolated cleanup. The fresh-copy notes below describe the earlier local handoff;
their then-pending remote verification is superseded by this follow-up.

## Fresh-copy verification: 2026-09-16

Copied commit-eligible source, including Stage 11 working changes, to
C:/Users/USER/AppData/Local/Temp/commonbeacon-handoff-517d2c93.
The copy contained no development .env, planning files, node_modules, target,
dist, or prior test output. Normal host tool/dependency caches and Docker image
layers were available; this was not an empty-machine benchmark.

Commands executed from the copy:

```powershell
cd frontend
npm ci
cd ..
./scripts/verify.ps1
cd frontend
npm run test:smoke
```

Observed output excerpts:

```text
Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Tests  49 passed (49)
All backend and frontend checks passed.
6 passed (17.2s)
```

There were also 5 passing Java unit tests, for **59 backend tests total**.
Lint, TypeScript checking, and the production build passed.

The browser suite built both images and used Nginx on port 4173, the packaged
Spring backend, and PostgreSQL tmpfs. The intentional-outage check used Vite on
4174 with an unreachable upstream. All generated test services were removed.

A deliberate no-matching-tests run returned exit 1. The runner saved logs,
removed its frontend/backend/database containers and network, and a Docker query
confirmed no containers remained for commonbeacon-e2e-failure-517d2c93.
This verifies local failure cleanup. Remote artifact upload later passed in the
Stage 11 run linked above.

actionlint 1.7.12 (checksum verified from the upstream release) accepted ci.yml
with exit 0. Local invocation disabled optional shellcheck; the subsequent Stage 11
GitHub run also verified hosted-runner execution.

## README startup verification

In the fresh copy, generated a new .env with temporary credentials, enabled
fictional demo seeding, and selected frontend port 4181. Used the separate project
commonbeacon-handoff-517d2c93 to avoid the development stack.

```powershell
docker compose --project-name commonbeacon-handoff-517d2c93 config --quiet
docker compose --project-name commonbeacon-handoff-517d2c93 up -d --build --wait --wait-timeout 180
```

All three services became healthy. The public health endpoint returned UP.
Getting started contained exactly two seeded questions; the solved question
included its accepted reply. A direct question URL returned the frontend page.
The disposable fresh-setup stack and its newly created volume were then removed.
The existing development stack and its volume remained healthy and unchanged.

Stage 9 separately verified persistence across backend/database restarts and
ordinary down/up, along with session expiry, using the repeatable
frontend/scripts/verify-compose.mjs check. See [Compose evidence](../compose.md).
Stage 10 inspected mobile/outage screenshots and tested long-content overflow
and visible keyboard focus. See [browser evidence](../browser-testing.md).

No local database/demo password was found in the commit-eligible source inventory.
.env, planning documents, logs, screenshots, and build output remain ignored.

## Five-minute demonstration

1. Start the development Compose stack and open http://127.0.0.1:8081.
2. Browse Getting started anonymously. Show its solved and unanswered examples.
3. Sign in as alex.member@example.test using your local DEMO_PASSWORD.
4. Ask a clear new question. Reload to show persistence.
5. In a separate private browser session, sign in as sam.member@example.test.
   Reply to Alex's question. Show that Sam cannot edit Alex's question.
6. Return to Alex's session. Accept Sam's reply, refresh, and show the solved filter.
   Clear it, then accept it again to demonstrate the versioned selection.
7. Open an edit in two tabs; save one and show the other receives a conflict
   while retaining its draft. Reload explicitly before retrying.
8. As avery.admin@example.test, archive the board. Show that content stays readable
   but writing and acceptance controls are closed. Reopen it afterwards.
9. Sign out. Explain why backend restart also signs users out while PostgreSQL
   retains conversations.

## Source walkthrough

Use the actual files alongside [architecture notes](../architecture.md):

- identity/SecurityConfiguration.java and IdentityService.java: role and owner rules.
- question/QuestionService.java: transaction boundaries, checks, and lock order.
- db/migration/V5__accepted_reply.sql: cross-question database invariant.
- question/Question.java: optimistic version and derived solved state.
- reply/ReplyRepository.java: visible content and relationship loading.
- AcceptedReplyIT.java: concurrent writers, constraints, and hidden-content regression.
- frontend/src/features/replies/useSolution.ts: explicit recovery without retrying writes.
- frontend/src/features/auth/AuthProvider.tsx: session refresh and cache clearing.

Java paths above are relative to backend/src/main/java/com/lawrencenno/commonbeacon,
except migrations (backend/src/main/resources) and tests (backend/src/test/java).
Personal interview fluency requires rehearsing this walkthrough; tests do not prove it.

## Known limitations and next work

- Local HTTP deployment only; Internet deployment needs TLS, proxy trust, and
  operational hardening.
- In-memory sessions and login throttling are single-instance; a proxy shares its
  socket-address rate limit among users.
- Board locking serializes writes across a board. This favors clarity over throughput.
- No email verification, password reset, MFA, moderation UI, search, articles,
  GraphQL, AI drafting, or multi-instance operation yet.
- Future moderation must atomically clear accepted replies when hiding them.
- Browser tests require Docker and Chrome, use fixed localhost ports, and run serially.
- Planning files remain local. CI requires GitHub Actions capacity and access to
  dependency/container registries.
- Stage 11 remote verification passed for 2d33c96; later revisions require their own checks.

Milestone B adds moderation, articles, and search. Milestone C adds GraphQL and
AI assistance after the core authorization and data rules remain verified.
