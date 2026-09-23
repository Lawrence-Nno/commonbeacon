package com.lawrencenno.commonbeacon.transfer.storage;

import java.io.IOException;
import java.time.*;
import org.springframework.scheduling.annotation.Scheduled;
import com.lawrencenno.commonbeacon.transfer.job.TransferJobs;

public final class ArtifactReconciler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ArtifactReconciler.class);
    private final TransferJobs jobs;
    private final ArtifactStore store;
    private volatile java.util.Map<String,Long> metrics=java.util.Map.of();
    private volatile long lastSuccess;
    private volatile long failures;
    public ArtifactReconciler(TransferJobs jobs,ArtifactStore store) {this.jobs=jobs;this.store=store;}
    public ArtifactReconciler(TransferJobs jobs,ArtifactStore store,io.micrometer.core.instrument.MeterRegistry registry) {
        this(jobs,store);
        for(var name:java.util.List.of("reserved_bytes","cleanup_backlog","staged_bytes","stale_jobs","active_jobs",
                "used_bytes","quota_bytes","usable_bytes","minimum_free_bytes"))
            registry.gauge("commonbeacon.transfer."+name,this,r->r.metrics.getOrDefault(name,-1L));
        registry.gauge("commonbeacon.transfer.cleanup_last_success_seconds",this,r->r.lastSuccess);
        registry.gauge("commonbeacon.transfer.cleanup_failures",this,r->r.failures);
    }
    public void reconcile() throws IOException {
        try {reconcileOnce();}catch(IOException | RuntimeException e){failures++;throw e;}
    }
    private void reconcileOnce() throws IOException {
        IOException failure=null;
        for(var artifact:jobs.artifacts()) {
            try {
                // Revoke expired/abandoned metadata before touching potentially broken storage.
                boolean cleanup=jobs.needsCleanup(artifact.id(),true);
                if(!cleanup && artifact.state().equals("AVAILABLE")) {
                    var stored=store.inspect(artifact.id());
                    boolean exists=stored.isPresent() && java.util.Objects.equals(artifact.bytes(),stored.get().bytes())
                        && java.util.Objects.equals(artifact.hash(),stored.get().sha256());
                    cleanup=jobs.needsCleanup(artifact.id(),exists);
                }
                // WRITING snapshots must never diagnose a concurrently published file as missing.
                if(cleanup) {store.delete(artifact.id());jobs.cleaned(artifact.id());}
            }catch(IOException | RuntimeException e) {
                com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"transfer.cleanup_failed",e,artifact.jobId());
                failure=new IOException("TRANSFER_CLEANUP_INCOMPLETE");
            }
        }
        try {
            var candidates=store.keysOlderThan(Instant.now().minus(Duration.ofMinutes(15)));
            var known=jobs.knownArtifactKeys();
            for(var key:candidates) {
                if(!known.contains(key))try {store.delete(key);}catch(IOException | RuntimeException e) {
                    com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"transfer.orphan_cleanup_failed",e,null);
                    failure=new IOException("TRANSFER_CLEANUP_INCOMPLETE");
                }
            }
        }catch(IOException | RuntimeException e) {
            com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"transfer.storage_scan_failed",e,null);
            failure=new IOException("TRANSFER_CLEANUP_INCOMPLETE");
        }
        // Database housekeeping must progress even when an individual file cannot be removed.
        jobs.releaseCleanedReservations();
        jobs.purgeExpiredMetadata();
        var snapshot=new java.util.LinkedHashMap<>(jobs.operationalSnapshot());
        try {
            store.usage().ifPresent(u->{snapshot.put("used_bytes",u.usedBytes());snapshot.put("quota_bytes",u.quotaBytes());
                snapshot.put("usable_bytes",u.usableBytes());snapshot.put("minimum_free_bytes",u.minimumFreeBytes());});
        }catch(IOException | RuntimeException e) {
            com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"transfer.storage_metrics_failed",e,null);
            failure=new IOException("TRANSFER_CLEANUP_INCOMPLETE");
        }
        metrics=java.util.Map.copyOf(snapshot);
        var log=LOG.atInfo().addKeyValue("event","transfer.storage_snapshot");
        snapshot.forEach(log::addKeyValue);log.addKeyValue("cleanupComplete",failure==null).log("Transfer storage snapshot");
        if(failure!=null)throw failure;
        lastSuccess=Instant.now().getEpochSecond();
    }
    @Scheduled(initialDelay=0,fixedDelay=900000)
    public void scheduledReconciliation() {
        try {reconcile();}catch(IOException | RuntimeException e) {
            // Never include file paths, exception messages or payloads in operational logs.
            com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(
                org.slf4j.LoggerFactory.getLogger(ArtifactReconciler.class),"transfer.reconciliation_failed",e,null);
        }
    }
}
