# CommonBeacon onboarding lessons

Enable local demo seeding with `DEMO_SEED_ENABLED=true` and your own 12-128
character `DEMO_PASSWORD`, then start the application using the README command.
Seeding is off by default, requires `local`, and is excluded with `prod` active.

## Three boards, nine questions, 45 visible answers

**Getting started** covers where to begin, how to write a useful question, and
how to register/sign in. **Product help** covers reporting, hidden/restored
answers, and accepted-answer ownership. **Using CommonBeacon** covers saved work,
search/knowledge guides, and archived-board restrictions.

Each board has three lessons. Each lesson has exactly five visible replies:
one accepted, accurate explanation and four answers labeled **Common misconception
(incorrect)**, each followed by a correction. Read the accepted explanation first,
then compare the alternatives. The labels prevent the training examples from
masquerading as trustworthy advice. Acceptance records the owner's choice; it is
not a general guarantee that all community answers are correct.

The lesson catalog is maintained in
`backend/src/main/resources/demo/onboarding.json`. It describes the actual product:
public reading, member contributions, private reports, owner-only acceptance,
plain-text content, session behavior, English full-text search, and archive rules.
The About page also describes the current capabilities.

## Operator examples

The existing four demo accounts remain: Alex and Sam are members, Morgan is a
moderator, and Avery is an administrator. All initially use your DEMO_PASSWORD.

- An OPEN report on the reporting lesson demonstrates the private review queue.
- A HIDDEN reply under the restoration lesson has a RESOLVED/HIDE report and a
  matching audit entry. This extra reply is private and is not one of the five
  visible onboarding answers. Restoring it does not change the selected correct
  answer; it will then become a sixth visible reply.
- **Writing a helpful question** is published at
  `/knowledge/demo-writing-a-helpful-question`.
- **Community review checklist** is an administrator draft at slug
  `demo-review-checklist`; public readers cannot access it until publication.

On a fresh untouched seeded database, the overview shows **0 unanswered questions,
1 open report, and 1 published article**. All nine lessons have selected answers.
Existing data and later actions can change these counts.

To verify moderation, create your own question and reply, accept the reply as
its owner, report it, then hide it as Morgan. The question becomes unanswered.
Restoring the hidden reply alone leaves it unaccepted. For article verification, use
Avery to edit/publish the draft, search its body, change its published text, and
archive it. Check public visibility and overview counts after each change.

## Existing development data and restarts

The original untouched Compose verification board is renamed **Using CommonBeacon**
and its persistence question/reply become the saved-work lesson, preserving their
IDs. Known original demo questions are expanded; a question/reply already edited
before this upgrade is left alone rather than overwritten. Other user boards and
content are not renamed or deleted. The one-time wording cleanup targets original
seed strings and IDs only.

The last misconception reply's deterministic ID marks a completed lesson bundle.
All startup changes share one transaction. On subsequent runs, lesson titles,
bodies, reply edits, hidden states, and accepted-answer choices remain untouched.
Article archival, resolved reports, restorations, and account credentials are
also preserved. This is not a reset command. Manually deleting seed records can
allow them to be recreated on a later enabled startup.

The removed random Browser board and Question smoke records are never recreated
by this seeder. Browser verification runs on its own disposable PostgreSQL stack.
`DemoOperationsIT` checks exact lesson counts, accepted reply content, legacy
conversion, and preservation on repeated runs; `onboarding.spec.ts` checks all
nine lessons through the public API and representative mobile pages.
