# Identity verification and session authorization

Stage 2 adds storage in Flyway V19; Stage 3 adds authoritative sessions and
permissions, with V20 connecting explicit epoch advances to transfer invalidation.
Stage 4 adds internal transactional challenge issuance and consumption; Stage 5
adds a private encrypted outbox in V21 and internal `EmailIdentity` orchestration. Public
verification/reset endpoints, email delivery, and pending registration remain later
stages. Registration still creates ACTIVE members.
Completing these stages does not close the production email launch gate.

## Upgrade and transition mode

Normal startup applies V19-V21 once after V18 and validates the JPA mapping.
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
  runtime rotation and consumption are implemented by the Stage 4 primitives.
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

## Stage 4 challenge primitives

`EmailChallenges` is an internal service, with no controller, token introspection API,
or automatic login. `issue` generates 32 bytes with injected `SecureRandom`, encodes
43 canonical unpadded base64url characters, and stores only a purpose-separated SHA-256
digest. Verification lifetime is 24 hours; reset and email-change lifetime is 30 minutes.
Parsing checks length/alphabet/canonical encoding before database work. A UTC clock is
injected; issuance timestamps use PostgreSQL microsecond precision so returned expiry
matches persisted expiry. Equality with expiry is expired, and a clock before creation
grants nothing.

Both issuance and consumption join the caller's transaction, or start a transaction
when called alone. They explicitly take the maintenance shared gate, exclusive identity
gate, subject row, then challenge/proposal rows. Never invoke them after taking transfer,
outbox, or other domain locks. Stage 8 must take durable rate-budget rows in the agreed
order before calling these primitives; the admission callback here is a precomputed
decision, not a substitute for unknown-address/global rate limits. Public email-change
issuance must additionally enforce owner and recent authentication in its Stage 11
orchestrator. There is no exposed issuance endpoint at this stage.

Issuance checks current account/address eligibility and a 60-second cooldown including
previous terminal challenges. An admitted resend advances only that purpose's generation,
revokes the prior challenge, and creates a new binding. A denied attempt changes no
challenge, generation, or proposal. Its `Issued` object is delivered only to an internal
transaction callback; Stage 5 must persist encrypted delivery intent there. The service
does not send mail or expose the token as a response. Do not retain/log callback secrets
or treat a callback value as committed until the enclosing transaction commits.

Consumption rechecks current state, purpose, address, generation, expiry, terminal state,
and email-change proposal. Expired, replayed, revoked, wrong-purpose, missing/ineligible
subject, stale binding, and address-collision links share `INVALID_EMAIL_LINK`. An eligible
pending verifier must choose a nonblank 12-128-character replacement password; invalid
passwords leave the token usable. Legacy ACTIVE verification retains credentials/role.
Verification establishes proof and invalidates sessions without creating a session.
Reset changes credentials/epoch but preserves state/proof and a pending verification
link; it cancels reset links and email-change proposals. Email change switches the address
and records new proof only on completion, and revokes old-address challenges. If the
existing proof timestamp equals the injected clock, new proof advances one microsecond
so the existing address-change trigger does not mistake it for unchanged proof.

The consumed marker, account transition, challenge revocations, proposal deletion, and
completion callback commit together. A callback failure or outer rollback preserves the
original account and token; a conflicting address cannot partially consume the link.
Stage 5 must persist security intents in the completion callback using the same transaction
and datasource. Callbacks must perform database work only, with no independent transaction,
network request, log of private values, or externally visible side effect. `Issued` and
`Completion` serialize as empty objects and print redacted representations; private
storage projections also redact their string representations. Bind diagnostics are OFF.

`EmailChallengesIT` covers real PostgreSQL expiry, cooldown, purpose/binding isolation,
password replacement, rollback, conflicting address, concurrent issuance/consumption,
resend/consume, and suspension. `EmailChallengeHttpIT` verifies GET/HEAD/POST to an
unimplemented link route cannot consume a challenge and request logs omit token/body
values. This is absence-of-mutation qualification, not an implemented landing page.

## Next stages

Stage 6 adds transport, templates, and isolated capture. Later stages implement HTTP
flows and landing pages: GET/HEAD render only, and explicit
CSRF-protected POST invokes consumption. Links will use fragments removed from browser
history and trusted configured origins. No SMTP provider or credentials are needed yet.

## Stage 5 encrypted outbox

`EmailIdentity` wires Stage 4 callbacks to `EmailOutbox`: challenge/generation changes
and an encrypted intent commit together; completion and required password/new/old-address
notices also commit together. Use this orchestrator for future public flows rather than
calling the low-level challenge primitive with an empty callback. An unavailable outbox
or failed intent rolls back the identity transaction, preserving an existing usable link.
No service makes a network call, starts a mail scheduler, or exposes a token/receipt API.

`email_outbox` stores subject/message/event IDs, template version, challenge/generation,
key ID, nonce/ciphertext, state, attempt count, next attempt, lease owner/version/expiry,
safe UUID provider correlation, timestamps, and an allowlisted failure code. Unique
subject/type/event bindings deduplicate semantic intents. Message bindings are immutable,
terminal rows cannot become sendable again, and a key/nonce pair cannot be reused while
stored. Outbox recipients and tokens occur only inside AES-256-GCM ciphertext, with fresh 96-bit
nonces and a 128-bit authentication tag. Authenticated context binds message, subject,
type/template version, event, challenge/generation, timestamps, and key ID. Payloads and
encrypted/storage objects print redacted representations; payload JSON is empty.
Existing private account/challenge address bindings retain their current schema.

States are QUEUED, SENDING, RETRY_WAIT, ACCEPTED, FAILED, CANCELLED, and EXPIRED.
ACCEPTED means provider submission acceptance, not inbox delivery or verification.
Claim/prepare/finalization are short transactions behind maintenance/identity gates.
One claim grants a 60-second fenced lease; a clock before creation grants no decryption
or finalization. Prepare rechecks current subject/challenge/
generation/address/proposal before decrypting. Its return holds no database lock.
Already prepared/submitted bytes cannot be recalled after suspension/resend/erasure;
future work is cancelled and old links remain invalid. Stage 7 must add bounded dispatch,
transport budgeting, shutdown coordination, and provider uncertainty qualification.

Transient failures preserve the exact ciphertext/nonce/token and original expiry.
There are at most six attempts, with 30-second, 2-minute, 10-minute, 30-minute, and
2-hour delays plus less than 10% positive jitter. Retry cannot extend lifetime. A retry
that would reach expiry expires the message. Provider acceptance, terminal failure,
cancellation, and expiry erase ciphertext/nonce and clear/fence leases. Expired uncertain
leases currently fail closed with LEASE_EXPIRED; Stage 7 must qualify recovery/operator
retry behavior instead of claiming exactly-once remote sending. Retention cleanup uses
bounded 200-row batches and removes terminal metadata after 30 days without deleting
accounts. It is an explicit primitive, not a scheduled dispatcher at this stage.

Challenge consumption/revocation/deletion cancels linked queued/leased messages through
database triggers. Suspension/import-inactive/erasure cancels subject mail, including
security notices. Account/company erasure phase 0 deletes outbox rows before challenge
and proposal rows, preserving existing durable phase numbering and including the retained
company administrator. The restore-ledger function preserves its public SQL signature,
closes the erasure barrier, and cancels/purges restored mail before returning; ordinary
claims are blocked until erasure replay finishes. Content export projections remain
allowlists and omit the whole outbox, ciphertext, keys, and identity secrets.

Security alert recipients are frozen inside the encrypted payload. Email-change completion
creates separate old/new-address alerts; a later account lookup must not redirect the
old-address warning. Password reset and pending activation queue password-change notices.
Version 1 security notices expire after 24 hours; link messages retain challenge expiry.

### External key setup and restore

Outbox creation is disabled by default. Set `EMAIL_OUTBOX_ENABLED=true`, an
`EMAIL_OUTBOX_ACTIVE_KEY` ID, and `EMAIL_OUTBOX_KEYS` from an external secret store.
The key ring format is comma-separated `key-id=base64-32-byte-key` entries. IDs allow
1-40 ASCII letters/digits/underscore/hyphen. Enabled startup requires a valid active key;
invalid/duplicate IDs, malformed base64, and wrong key sizes fail with a redacted error.
There is no generated/default deployment key. `.env.example` keeps all secret values
blank. Avoid putting real values in Git, command histories, logs, or shared Compose dumps.

For rotation, add a new independently generated AES-256 key to the external ring, switch
the active ID, and retain old IDs for decryption until their queued/retry/leased rows are
resolved or explicitly expired/purged. Changing the active ID does not rewrite tokens,
payloads, or expiry. Key loss marks affected messages FAILED/KEY_UNAVAILABLE and purges
the unusable body; never regenerate a token or extend expiry as a transport retry.
After restoring a backup, keep network/mail dispatch closed, restore required external
keys separately, replay the separately retained erasure ledger with the existing offline
script, then qualify eligibility before dispatch. Missing keys fail closed; encrypted
backups still contain private data and require the documented backup retention policy.

`EmailOutboxIT` covers ciphertext/deduplication, rollback, service/database restart,
retries/fencing/exhaustion, stale eligibility, key rotation/loss, immutable bindings,
retention, frozen alert recipients, suspension/erasure, and restore replay. Existing export
and erasure tests include actual encrypted mail fixtures. V20 upgrade and transactional
V21 failure/retry tests verify migration safety. No provider was contacted.
