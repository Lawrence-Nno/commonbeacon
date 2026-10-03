# Company migration guide

Use this guide with an ACTIVE administrator on each deployment. The supported
target is an empty CommonBeacon installation with demo seeding disabled and only
ACTIVE bootstrap administrators. A populated community is not a merge target.
Read the [supported scope and release evidence](transfer-scope.md) before scheduling
a migration; imported authors cannot sign in or claim historical accounts.

## Prepare the deployment

Follow the README's fresh-checkout steps, use a unique database password in `.env`,
and leave `DEMO_SEED_ENABLED=false`. Enable durable private transfer storage:

```powershell
docker compose -f compose.yaml -f compose.transfers.yaml config --quiet
docker compose -f compose.yaml -f compose.transfers.yaml up -d --build --wait --wait-timeout 180
```

Use the same files and project name for subsequent operations. The database and
transfer artifacts are separate named volumes. Ordinary `docker compose ... down`
preserves them; `down --volumes` deletes them. Do not reuse a development project's
name, volumes or `.env` for an import target. Keep the artifact directory private,
off public web roots, and include it in coordinated backup/restore procedures.

**Administrator provisioning is a remaining production dependency.** Registration
creates MEMBER accounts only. There is no supported non-demo administrator bootstrap,
invitation or role-management workflow. Existing authorized administrators can use
the migration tools; a fresh company cannot complete onboarding using the supplied
product alone. Test suites provision disposable administrators through fixture SQL.
That is test setup, not a supported operator procedure. Enabling demo data is not a
workaround: a seeded target is ineligible for activation.

## Export and check the source

1. Open **Data management** (`/admin/data`) on the source and choose company export.
   Acknowledge inclusion of hidden content and unpublished articles. Contacts and
   moderation history are separate optional private sections, off by default.
2. Confirm the current password. Keep the job ID and wait for READY. Export uses a
   consistent database snapshot; changes after that snapshot are not in the archive.
   Arrange an operational cutover if writes must not be lost between deployments.
3. Download through the protected action. Downloads require another password
   confirmation and a one-use ticket; the archive is available for 24 hours.
   Store it privately. Neither export nor download deletes source data.
4. Check the ZIP's `manifest.json`, entity counts, exclusions and checksums using
   the [native format](data-archive-format.md). Do not modify an archive to bypass
   validation. The target's inspection verifies structure and relationships;
   inspection alone never publishes content.

For supported external input, follow the [Discourse 3.5.0 export-helper guide](discourse-import.md)
and select DISCOURSE on upload. Only that documented bundle is supported, not a raw
Discourse backup. Other vendor exports have no verified adapters.

## Upload, review and activate

1. On the empty target, open **Data management → Imports** (`/admin/data/imports`).
   Select the provider and local file, confirm the password, and upload. Native input
   is a company-profile ZIP; personal archives are deliberately rejected.
2. Wait for inspection and inspect its errors. Correct invalid source data and
   create a new archive; do not treat REVIEW_REQUIRED as validation success.
3. Request a dry run. It stages private records without writing community data.
   Check every count, warning, optional exclusion, mapping preview and target check.
   READY_TO_COMMIT requires an eligible target and a fresh, error-free review.
4. Acknowledge the actual warning set, private content and inactive imported authors,
   then confirm the password for activation. COMMITTING means accepted, not finished.
   Wait for COMPLETED. Activation publishes all supported records atomically.
5. Open reconciliation. Check expected/created/skipped/rejected counts and page through
   mappings as needed. Save the outcome report privately and spot-check relationships,
   timestamps, visibility, accepted replies and article states. Source contacts remain
   private provenance; imported roles and passwords are never applied.

The [screen guide](import-ui.md), [dry-run protocol](import-dry-run.md), and
[activation protocol](import-activation.md) cover exact actions and confirmations.
API clients must retain cookies, use fresh CSRF tokens, and follow the UUID
idempotency keys, expected versions and digests in [OpenAPI](openapi.json).

## Limits, interrupted work and cutover

Native input is at most 64 MiB compressed, 256 MiB total JSONL and 40,000 records;
per-entity, row, entry, depth and compression-ratio limits also apply. Discourse
input is at most 8 MiB. Activation additionally allows at most 16 MiB of staged
JSON and a 30-second transaction. These are independent limits: a ZIP under 64 MiB
can still be rejected. See [all format limits](data-archive-format.md) and
[measured capacity](transfer-verification.md); qualify your own infrastructure.

After an interrupted upload, restart the whole file only if the job remains
UPLOADING. After a lost activation response, reopen the same job and reconciliation;
API clients replay the original confirmation key/body. Never create a second import
to guess whether a commit succeeded. Cancellation is allowed before COMMITTING.
Pre-commit failure rolls back; completed imports have no automatic undo.

Backend restarts invalidate sessions and password grants. Sign in again, reopen the
job and obtain new grants when needed. The worker uses durable leases and completion
records to recover or reconcile; do not manually change job states or remove volumes.
Follow [recovery and diagnostics](transfer-operations.md) for expired leases, missing
artifacts, disk pressure and failed cleanup. Ordinary temporary-data cleanup does not
erase imported community records or their durable provenance.

Keep the old deployment and backups under your retention policy until reconciliation
and cutover are accepted. [Account/company erasure](offboarding.md) is a separate,
explicitly confirmed operation. It cannot recall downloaded copies or purge backups
managed outside CommonBeacon. There is no automatic delta sync or post-import merge.
