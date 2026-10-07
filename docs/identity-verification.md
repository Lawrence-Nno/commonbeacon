# Identity verification and session authorization

Stage 2 adds storage in Flyway V19; Stage 3 adds authoritative sessions and
permissions, with V20 connecting explicit epoch advances to transfer invalidation.
Token issuance, verification/reset endpoints, email delivery, and pending
registration remain later stages. Registration still creates ACTIVE members.
Completing these stages does not close the production email launch gate.

## Upgrade and transition mode

Normal startup applies V19 and V20 once after V18 and validates the JPA mapping.
All existing addresses have `email_verified_at = NULL`: their verification is
unknown. Existing credentials, roles, content, and transfer authorization revisions
are preserved. No timestamps are inferred from prior use or administrator status.

`commonbeacon.identity.verification-mode` defaults to `TRANSITION` and accepts
`ENFORCED`. It can be set through `EMAIL_VERIFICATION_MODE`, including Compose's
backend environment. An unsupported value fails startup.

- TRANSITION retains full community/company access for eligible legacy ACTIVE
  accounts according to their current roles, including addresses of unknown proof.
- ENFORCED requires ACTIVE plus proof of the current address for community writes,
  staff reads/actions, and company export/import/erasure.
- PENDING_VERIFICATION is always limited, regardless of mode. In ENFORCED, an
  ACTIVE account with unknown proof is also limited. Both may browse public content,
  manage their own PERSONAL_EXPORT jobs, confirm scoped privacy passwords, and
  use own ACCOUNT erasure subject to the existing last-administrator rule.
- SUSPENDED, IMPORTED_INACTIVE, and ERASED cannot log in or use session-based privacy
  access. Imported/erased credential and irreversible-state protections remain intact.

Keep production in TRANSITION until the remaining verification flows and legacy
rollout are qualified. Do not switch an installation whose administrators cannot
complete the implemented verification workflow. The V18 last-ACTIVE-administrator
predicate remains unchanged; its verified-administrator replacement is Stage 13.

## Private identity storage

- `app_user` supports ACTIVE, PENDING_VERIFICATION, SUSPENDED, IMPORTED_INACTIVE,
  and ERASED. Pending identities require MEMBER credentials and have no proof.
  Imported/erased identities retain their existing non-claimable protections and
  cannot hold login credentials or verification proof.
- `email_verified_at` represents proof for the current address. Changing an address
  without new proof clears the old proof; erasure always clears it.
- `auth_epoch` is durable session invalidation state. The database advances
  it with security-relevant account changes. Verification changes also advance
  the existing transfer `auth_revision`. Explicit epoch advancement also advances
  transfer revision through V20; the two retain separate meanings and starting values.
- Verification, password-reset, and email-change generations are independent,
  nonnegative, and cannot decrease. Updating a generation alone does not invalidate
  unrelated sessions or transfer grants.
- `email_challenge` stores a unique 64-character lowercase hexadecimal digest,
  subject UUID, purpose, normalized intended address, generation, expiry, terminal
  timestamps, and attempts bounded to 0–10. It stores no raw token or verification URL.
  Binding fields are immutable and terminal challenges cannot be reopened.
- A partial unique index permits one nonterminal challenge per subject/purpose.
  Expired nonterminal rows must be explicitly revoked before replacement. Issuance
  must update the purpose generation and revoke the prior row in one transaction;
  admission and consumption logic are Stage 4.
- `pending_email_change` holds one private proposal per subject. It requires an
  ACTIVE verified subject and matching email-change generation. The challenge must
  match its address/generation and cannot outlive the proposal. Creating it never
  changes the current login email.

Both private tables use the existing migration/erasure and identity lock gates.
They are included in account/company erasure, including the retained company
administrator, and cascade on subject deletion. Suspension removes their rows.
Portable personal/company exports retain explicit projections and exclude these
records, proofs, counters, and digests. Database backups still contain private
identity data and follow the existing backup/erasure policy.

## Authoritative sessions and transaction boundaries

Email is a login lookup/input only. The authenticated principal carries the stable
account UUID and login-time epoch; the session retains `authenticatedUserId` and
`authenticatedEpoch`. IdentityService and TransferAccess resolve by UUID without
email fallback. Session rotation, CSRF, logout, and no-store account responses remain.
Sessions created before this principal format must sign in again.

Every authenticated request rechecks state and epoch. Suspension, role change,
password/address change, verification change, or explicit epoch advancement makes
old sessions invalid: they receive 401 UNAUTHENTICATED and lose their context/session.
Verification/activation never promotes cached limited authorities; fresh login is
required. Login against an ineligible identity retains the generic credential error.
CSRF retrieval and a new login can recover after clearing an old stale session.

A valid limited account denied a community/staff action receives 403
EMAIL_VERIFICATION_REQUIRED; ordinary role denials remain 403 FORBIDDEN. Own privacy
namespaces still enforce requester ownership, personal-job kind, CSRF, scope-specific
recent password proof, receipt secrets, and explicit erasure consent. A generic
DOWNLOAD grant cannot authorize company access for a limited account.

Protected services with existing PreAuthorize annotations use the authoritative
AccountAuthorization advisor, including private reads without Authentication
parameters. Supported rules are current member, administrator, and moderator/admin
access; an unsupported rule fails closed. Protected calls require a transaction.
The order is transaction, maintenance gate, shared identity gate/current-account
row check, method-security check, then domain locks. Protected private reads retain
their snapshot isolation and use lock-capable transactions. Public reads remain
separate. Transfer transactions take maintenance/identity gates before control/job
locks; erasure takes exclusive gates before requester checks.

The identity lock and account recheck survive to commit. A protected operation that
already obtained permission may finish and commit before a competing identity change;
later calls using the old epoch fail. Operations do not recall committed content or
bytes already returned. A competing identity change can produce retryable 409
IDENTITY_CHANGE_IN_PROGRESS rather than allowing a stale write.

Recent-auth grants/tickets bind UUID, epoch, transfer revision, session, purpose,
and expiry. Workers recheck requester eligibility and persisted revision at claim,
heartbeat/checkpoint, and publication. Snapshot producers check requester, persisted
job kind, and revision; personal snapshots include eligible pending owners. Long
export production does not hold the identity lock throughout file I/O: revoked work
cannot publish its private artifact. Upload/download loops recheck current permission;
completed chunks cannot be recalled. Cleanup of an existing fenced upload or owned
download lease remains possible after session revocation and grants no new access.

## Safe responses and frontend

Registration, login, and `/api/v1/auth/me` include `accountState`, `emailVerified`,
and explicit capabilities (`contribute`, `moderate`, `administer`, `personalData`,
`eraseAccount`) alongside UUID, display name, and role. No credential, private pending
address, digest, generation, epoch, or internal revision is exposed.

The frontend validates capabilities, uses them for staff/navigation/contribution
controls, and shows a static limited-account notice. It still reads older test/rollout
response shapes; state-aware responses lacking capabilities fail closed for community
actions. Session/role/state/proof/capability changes clear actor-private query caches
even when UUID is unchanged. Backend services remain the authority.

## Next stages

Stage 4 must recheck current state, address, generation, expiry, and terminal status
when consuming a challenge, including after the account changes following issuance.
SMTP configuration and actual outbound delivery belong to later stages. No email
provider account or credentials are required to run Stages 2-3.
