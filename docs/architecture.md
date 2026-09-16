# Architecture decisions and Java discussion notes

## 1. One backend application

Spring MVC controllers validate transport input and call transactional services.
Repositories handle persistence; DTO records define public responses. Keeping
identity, boards, questions, and replies in one application makes database
transactions and debugging understandable for this learning project. Separate
services would add deployment and consistency costs without a current requirement.

Discuss: follow a request from controller to service to repository; explain why
authorization and business checks belong on the server even when buttons are hidden.

## 2. Server-side sessions

The browser holds an HttpOnly, SameSite=Lax session cookie, not a token in local
storage. Spring Security manages login and session-ID rotation. Every write needs
CSRF protection. In-memory sessions simplify one-instance development but disappear
on restart; scaling requires shared session storage or another deliberate design.
Local HTTP disables Secure only in the local profile; production enables it.

Discuss: distinguish authentication, role checks, ownership, CSRF, and XSS.
Explain why a valid session alone does not authorize selecting another author's answer.

## 3. Flyway owns schema changes

Versioned SQL migrations establish tables, constraints, and indexes. Hibernate
validates the schema instead of changing it automatically. Foreign keys protect
references even when application code is bypassed. Applied migrations are immutable;
later changes require another migration.

Discuss: explain the composite foreign key
question(id, accepted_reply_id) -> reply(question_id, id). It ensures the selected
reply belongs to that question. A single nullable reference permits at most one
selection, without a second persisted solved flag.

## 4. Locks and versions protect different things

Acceptance locks board -> question -> selected reply, then rechecks permissions,
visibility, archival state, membership, and expectedVersion. The board lock
coordinates archival but serializes unrelated conversations on that board.
This is an explicit simplicity/throughput tradeoff.

The expected version rejects an outdated user's intent. JPA @Version guards
versioned persistence updates. Database constraints enforce structural integrity.
None replaces ownership checks. Two racing edits must produce a success and a
conflict rather than silently lose a write.

Public solved status also requires a visible referenced reply. Defensive reads
do not repair storage. Future moderation must clear an accepted reference in the
same transaction that hides it, following the same lock order.

Discuss: walk through two browser tabs saving version 0. Explain what the database
lock guarantees and why a lock alone does not detect a stale draft.

## 5. Explicit response loading

JPA relationships are lazy. Entity graphs fetch the relationships needed for list
and detail DTOs; DTO conversion happens within transactions. open-in-view is off.
JSON serialization receives records, not entities or lazy proxies. Public author
objects omit email and password hash.

Discuss: show how returning entities could cause extra queries, recursion, or data
exposure; distinguish a foreign key, a Java relationship, and a response DTO.

## 6. Same-origin deployment and testing

Nginx serves the compiled frontend and proxies /api. Docker DNS names db and backend
are container addresses; the browser uses localhost. PostgreSQL's named volume
survives ordinary shutdown. Browser tests use a separate project and tmpfs database.
Unit tests cover small logic; integration tests prove SQL constraints/transactions;
browser tests prove cookies, focus, routing, and the connected user journey.

Use these prompts to explain the actual source during an interview. Test results
demonstrate behavior; they do not establish personal understanding without practice.
