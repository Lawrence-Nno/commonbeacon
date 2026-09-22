# Backend operational logs

The backend writes one JSON object per log event to stdout using Spring Boot's
built-in Logstash format. No collector service is required to emit these events.
Framework logs use the same format. Application diagnostics deliberately omit
raw exception messages and throwable serialization.

## Investigating a failure

From the deployed Compose project directory:

```powershell
docker compose logs --since 30m --tail 200 backend
docker compose logs --no-log-prefix --since 30m backend | Select-String -SimpleMatch 'REQUEST-ID-HERE'
```

Every synchronous request handled by the application filter receives a fresh
server-generated `X-Request-Id`. API problem bodies reuse that ID. Supplied request
IDs are ignored. The ID is present in application-thread logs through MDC and the
previous thread context is restored at completion. Request IDs are diagnostic
references, not authentication credentials. Browser network tools expose the
response header; the current UI does not display an error-reference panel.

`http.request_completed` includes `requestId`, `method`, `route`, `status`,
`durationMs`, and `failed`. Routes are MVC templates, such as `/api/v1/boards/{id}`,
or `unmatched` when security or another filter returns before MVC routing. Raw
URLs, query strings, headers, bodies, IP addresses, and account identities are not
recorded. Successful and expected client-error completions use INFO; server
failures use ERROR. A committed response can still have status 200 after a late
failure: check diagnostic events as well as status. Health probes are included.

`http.unexpected_failure` records unexpected MVC/filter failures. Clients receive
a generic 500 problem when the response can still be written. Existing expected
API errors retain their behavior; framework client errors retain their HTTP
status and headers. Diagnostics contain exception class names (up to eight causes)
and application class/method/line locations (up to 24), excluding messages,
suppressed exceptions, file paths and SQL values. This intentionally provides
less detail than an unrestricted stack trace.

Export events include `export.claimed`, `export.ready`, `export.publish_rejected`,
`export.attempt_failed`, `export.heartbeat_failed`, `export.cleanup_failed`, and
`export.worker_failed`. Job-specific events include `jobId`; publication events
also include `fence` and `durationMs`. Search by job ID for background work:
it runs independently of the originating HTTP request. Reconciliation failures
emit `transfer.reconciliation_failed`; download I/O failures emit
`transfer.download_failed` with a job ID and the active request ID. Database job
state and audit history remain authoritative for cancellation, recovery and
terminal outcomes.

## Boundaries and remaining production work

This is an application logging foundation, not complete production observability.
It does not install centralized collection, retention/rotation, dashboards,
alerting, browser telemetry, or distributed tracing. Container logs may disappear
when containers are removed. Deployments must configure retention and access.
Async servlet processing is not currently used; introducing it requires explicit
context propagation and completion logging.

The allowlist applies to the new application events, not arbitrary third-party
logs. Hibernate JDBC error loggers that can print SQL values are disabled by
default. Do not enable SQL binding, request-body, security DEBUG/TRACE or other
verbose logging in production without reviewing data exposure. Other infrastructure
logs (including Nginx access logs) need a separate privacy/retention review.

Tests verify actual JSON output, security and MVC correlation, ignored incoming
IDs, status/header preservation, response resets, MDC cleanup, and exclusion of
sensitive inputs and nested exception messages from application diagnostics.
