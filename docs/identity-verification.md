# Identity verification groundwork

Stage 2 adds storage in Flyway V19. It does not implement token issuance,
verification/reset endpoints, email delivery, or pending registration. Existing
registration still creates ACTIVE members; login and permissions retain their
current behavior. Email enforcement is not a completed production launch gate.

## Upgrade and transition mode

Normal application startup applies V19 once after V18 and validates the JPA mapping.
All existing addresses have `email_verified_at = NULL`: their verification is
unknown. Existing credentials, roles, content, and transfer authorization revisions
are preserved. No timestamps are inferred from prior use or administrator status.

`commonbeacon.identity.verification-mode` is explicitly `TRANSITION`. This is the
only supported value in this stage; an unsupported value fails startup. Later
stages must implement authoritative service/session guards and a qualified legacy
account rollout before adding an enforcement mode. The existing V18 last-ACTIVE-
administrator protection remains unchanged.

## Private identity storage

- `app_user` supports ACTIVE, PENDING_VERIFICATION, SUSPENDED, IMPORTED_INACTIVE,
  and ERASED. Pending identities require MEMBER credentials and have no proof.
  Imported/erased identities retain their existing non-claimable protections and
  cannot hold login credentials or verification proof.
- `email_verified_at` represents proof for the current address. Changing an address
  without new proof clears the old proof; erasure always clears it.
- `auth_epoch` is durable session invalidation groundwork. The database advances
  it with security-relevant account changes. Verification changes also advance
  the existing transfer `auth_revision`. Session checks using the epoch are Stage 3.
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

## Safe responses and next stages

Registration, login, and `/api/v1/auth/me` add `accountState` and `emailVerified`
to the existing UUID, display name, and role response. No credential, pending
address, digest, generation, or internal revision is exposed. The frontend accepts
and validates these additive fields while still reading older response fixtures;
it does not grant or restrict authority based on them in this stage.

Stage 3 must enforce UUID/epoch and account eligibility in authoritative services.
Stage 4 must recheck current state, address, generation, expiry, and terminal status
when consuming a challenge, including after the account changes following issuance.
SMTP configuration and actual outbound delivery belong to later stages. No email
provider account or credentials are required to run Stage 2.
