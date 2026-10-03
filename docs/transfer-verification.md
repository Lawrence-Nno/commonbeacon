# Transfer integration and recovery verification

Stage 16 exercises the delivered native, Discourse 3.5.0 bundle, and offboarding
scopes. It adds repeatable recovery and capacity checks; it does not certify a
production installation, multi-backend operation, arbitrary Discourse backups,
Bettermode, Vanilla, or another unverified adapter.

## Run the checks

Use the pinned tools in [development setup](development-setup.md), with Docker
running. From the repository root:

```powershell
./scripts/verify.ps1
npm --prefix frontend run test:smoke
npm --prefix frontend run test:recovery
npm --prefix frontend run test:recovery-cleanup
npm --prefix frontend run test:persistence
npm --prefix frontend run test:failure-cleanup
```

The ordinary smoke suite covers native source-to-destination reconciliation,
disconnected upload/confirmation responses, private visibility, cache clearing,
Discourse, and erasure. Backend tests additionally compare every native entity's
fields and references after re-export, check identity/provenance, fresh/upgrade
migrations and Hibernate validation, and bypass the UI to test authorization.

`test:recovery` uses two unique `commonbeacon-e2e-*` Compose projects, explicit
`.env.example`, and `compose.recovery-e2e.yaml`. Each has separate PostgreSQL and
artifact volumes. Ports 4173, 4174 and 4176 must be available. Before entering
cleanup's try/finally, the runner refuses to adopt existing containers, networks
or volumes with either exact project label. Both projects and their volumes are
removed and checked for leftovers on success/failure. `test:recovery-cleanup`
injects an exception after both instances start and independently checks cleanup.
Development `.env` and volumes are never loaded or mounted.

## Crash boundaries

The recovery suite pauses transactions with temporary functions installed only
in its disposable database and observes the pause before SIGKILL. There is no
production fault endpoint. After killing the owning backend it terminates only
the artificial sleeping SQL connection, removes the trigger, and expires that
test job's lease. This is synthetic clock advancement, not a measured 60-second
wait or a change to production timeouts.

- Export after ZIP creation/before READY: a new fenced attempt publishes a whole
  archive; no partial result becomes downloadable.
- PostgreSQL at READY: identical archive bytes after recovery. Consumed tickets
  cannot be reused; backend restarts invalidate in-memory sessions.
- Partial streamed upload: full retry succeeds; incomplete bytes remain private.
- Staging: interrupted batch rolls back and retry reaches READY_TO_COMMIT without
  duplicate staging rows or domain writes.
- Activation after all inserts/before the completion marker: complete rollback,
  ACTIVATION_RECONCILIATION_REQUIRED, no automatic reinsertion. A deliberate new
  reviewed import can complete.
- PostgreSQL after activation: unchanged graph fingerprint and durable receipt.
- Cleanup after physical deletion/before metadata commit: deletion retries safely,
  staging/reservations are reclaimed, and activated data remains.
- Backend and PostgreSQL during company erasure: maintenance remains effective,
  the private receipt resumes, and tombstone/sole bootstrap administrator remain.

`CompanyExportIT` also kills a PostgreSQL backend process during extraction and
ZIP writing. PostgreSQL terminates all other sessions and performs crash recovery
without changing Testcontainers' random published port. Interrupted snapshots
fail without publication; packaging a completed snapshot can finish after recovery.
Existing tests cover stale workers, lost heartbeats, concurrent claims, role
changes, expired grants/tickets, download leases, all activation rollback groups,
lost commit responses, hostile archives, credential omission, and cross-job access.
An otherwise valid archive over the 16 MiB activation budget is rejected before
activation; cancellation must reclaim its staging, artifacts and reservation.

## Measurements and gates

All shared PostgreSQL integration fixtures explicitly enable `fsync`,
`synchronous_commit`, and `full_page_writes`; Testcontainers' faster default
`fsync=off` is unsuitable for durability/capacity evidence.

`ImportActivationIT.measuredPipelineEnvelope` runs 400, 4,000 and 40,000 records,
repeating the maximum. Maximum distribution: 2,000 users, 100 boards, 5,000
questions, 20,000 replies, 5,000 acceptances, 1,000 articles, 2,000 contacts,
3,900 reports and 1,000 actions. Small/representative counts divide these by
100/10. Repeated synthetic bodies have distinct suffixes. The maximum payload is
16,621,800 staged bytes; ZIP entries are stored. Two target bootstrap accounts
are outside imported counts.

`TRANSFER_BENCHMARK` records inspection, staging, activation and total elapsed
time; WAL growth; database/index sizes; artifact bytes; sampled whole-JVM heap
and lock waiters; and concurrent GET /boards p95/max latency and sample count.
Sampling runs roughly every 100 ms. Fixture construction is excluded from elapsed
time but included in heap. Cache conditions are uncontrolled/warm-host, not cold.
WAL/database figures include sampler and worker metadata. Heap samples are not
RSS or allocation totals. Concurrent worker admission/fencing is tested separately;
this read sampler is not a claim about arbitrary foreground write traffic.

`CompanyExportIT.measuresSnapshotAndPackagingAtSupportedCounts` measures 405,
4,005 and exactly 40,000 source rows. Maximum distribution: 4 users, 100 boards,
5,000 questions, 20,000 replies, 5,000 acceptances, 1,000 articles, 2 contacts,
5,000 reports and 3,894 historical actions. It records snapshot and
validation/packaging time, intermediate and ZIP bytes, then validates the archive.
These are separate source fixtures: importing at the maximum adds bootstrap
users, so a later full re-export can exceed a user/row ceiling and be rejected.
Limits are not raised to conceal that outcome.

Assertions retain 30-second activation, 120-second snapshot, 10-minute pipeline
and packaging, 64 MiB ZIP and 768 MiB reservation ceilings. Sampled foreground
p95 must be under 2 seconds and maximum under 5 seconds; sampled whole import-test
JVM heap must be under 1 GiB. These test thresholds do not increase application
input or storage limits. The 8 MiB streaming-buffer design budget does not mean
the whole JVM or row-bounded relationship index fits in 8 MiB. Production sizing
requires complete process/database/host measurements under the intended workload.
`productionCapacityCertified` remains false.

## Evidence and CI

Local measurements on 2026-10-03 used PostgreSQL 18.6, Java 21.0.12.1 and Docker
Desktop on Windows with 16 Docker CPUs and 20,566,962,176 bytes (19.15 GiB) of
Docker memory. The full regression JVM and disposable recovery stacks shared the
host; caches were not flushed. These are synthetic local qualification results:

- 400 imported rows: 153 ms inspection, 1,247 ms staging, 131 ms activation,
  1,723 ms pipeline; foreground p95/max 18/18 ms over 15 samples.
- 4,000 imported rows: 259 ms inspection, 16,426 ms staging, 2,237 ms activation,
  19,887 ms pipeline; foreground p95/max 9/14 ms over 172 samples.
- 40,000 imported rows, first run: 897 ms inspection, 122,822 ms staging,
  7,296 ms activation, 133,001 ms pipeline; foreground p95/max 7/20 ms over
  1,168 samples. WAL growth 97,841,912 bytes; database growth 73,400,320 bytes;
  indexes 26,877,952 bytes; sampled JVM heap 476,128,288 bytes.
- 40,000 imported rows, repeat: 853 ms inspection, 141,463 ms staging,
  5,904 ms activation, 150,252 ms pipeline; foreground p95/max 8/42 ms over
  1,309 samples. WAL growth 115,481,888 bytes; database growth 73,331,888 bytes;
  indexes 26,648,576 bytes; sampled JVM heap 495,984,704 bytes.
- Both maximum runs used 16,664,561 artifact bytes and 16,621,800 staged bytes.
  No lock waiters were observed by the sampler; this does not prove no short wait
  occurred between samples. Cleanup assertions require zero remaining artifact
  bytes, staging rows and reserved bytes.
- Export 405/4,005/40,000 rows: snapshot 68/324/1,507 ms; validation and packaging
  70/232/1,069 ms. The maximum intermediate was 17,905,463 bytes and its ZIP
  2,019,284 bytes. Output archives passed the native validator.

Maven reports contain measurements; Playwright attaches verified crash boundaries
and saves failure traces/logs in ignored output directories. Fixtures and accounts
are synthetic. Never put real company archives, credentials or private records
into these jobs or uploaded artifacts.

CI runs native browser round trips/persistence, backend recovery/benchmarks, and
a separate bounded two-instance recovery job. Fallback cleanup includes both
project names. Reports are retained seven days. Local results do not establish
successful remote CI: record the exact commit/workflow after committing and
pushing. Stage 17 handoff and deployment-specific capacity/backup/launch gates
remain separate.
