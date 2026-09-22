package com.lawrencenno.commonbeacon.transfer.job;

import java.time.Instant;
import java.util.UUID;

public record TransferJob(UUID id, UUID requester, Kind kind, State state, long version,
        long fence, int attempts, UUID worker, Instant leaseUntil, long checkpoint,
        Instant createdAt, Instant updatedAt, Instant expiresAt, Failure errorCode) {
    public enum Kind { COMPANY_EXPORT, PERSONAL_EXPORT, COMPANY_IMPORT }
    public enum State { QUEUED, RUNNING, READY, FAILED, CANCELLED, UPLOADING, UPLOADED,
        VALIDATING, REVIEW_REQUIRED, READY_TO_COMMIT, COMMITTING, COMPLETED }
    public enum Failure { WORK_FAILED, AUTHORIZATION_REVOKED, ATTEMPTS_EXHAUSTED,
        JOB_EXPIRED, ARTIFACT_MISSING, ACTIVATION_RECONCILIATION_REQUIRED, INVALID_SOURCE_DATA, TRANSFER_LIMIT_EXCEEDED, SNAPSHOT_TIMEOUT }
    public enum Event { CREATED, CLAIMED, CONFIRMED, COMPLETED, FAILED, CANCELLED,
        DOWNLOAD_ATTEMPT, DOWNLOAD_COMPLETED, EXPIRED, CLEANUP_STARTED, CLEANUP_COMPLETED, LEASE_EXPIRED, UPLOADED, INSPECTED }
    public record Lease(UUID jobId, UUID worker, long fence) {}
    public Lease lease() { return new Lease(id, worker, fence); }
    public boolean terminal() { return state == State.READY || state == State.FAILED || state == State.CANCELLED || state == State.COMPLETED; }
}
