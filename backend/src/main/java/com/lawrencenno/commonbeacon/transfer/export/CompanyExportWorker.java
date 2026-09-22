package com.lawrencenno.commonbeacon.transfer.export;

import com.lawrencenno.commonbeacon.transfer.job.*;
import com.lawrencenno.commonbeacon.transfer.storage.*;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/** Shared fenced export orchestration with separate company and personal projections. */
public final class CompanyExportWorker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CompanyExportWorker.class);
    private final TransferJobs jobs;
    private final ArtifactStore store;
    private final CompanySnapshot snapshot;
    private final PersonalSnapshot personalSnapshot;
    private final UUID worker=UUID.randomUUID();
    public CompanyExportWorker(TransferJobs jobs,ArtifactStore store,CompanySnapshot snapshot) {
        this(jobs,store,snapshot,null);
    }
    public CompanyExportWorker(TransferJobs jobs,ArtifactStore store,CompanySnapshot snapshot,PersonalSnapshot personalSnapshot) {
        this.jobs=jobs;this.store=store;this.snapshot=snapshot;this.personalSnapshot=personalSnapshot;
    }
    private void cleanup(UUID job) throws IOException {
        for(var artifact:jobs.artifacts())if(artifact.jobId().equals(job)) {
            // WRITING intermediates and stale attempts are never download candidates.
            if(jobs.needsCleanup(artifact.id(),true)){store.delete(artifact.id());jobs.cleaned(artifact.id());}
        }
    }
    public boolean runOnce() {
        var claimed=personalSnapshot==null?jobs.claimCompanyExport(worker):jobs.claimExport(worker);if(claimed.isEmpty())return false;
        var job=claimed.get();var lease=job.lease();var alive=new AtomicBoolean(true);
        long started=System.nanoTime();
        LOG.atInfo().addKeyValue("event","export.claimed").addKeyValue("jobId",job.id().toString()).addKeyValue("kind",job.kind().name()).log("Export claimed");
        long deadline=System.nanoTime()+600_000_000_000L;
        CompanyArchive.Check check=()->{if(!alive.get() || Thread.currentThread().isInterrupted() || System.nanoTime()>deadline)throw new IOException("EXPORT_INTERRUPTED");};
        try(var heartbeat=Executors.newSingleThreadScheduledExecutor()) {
            heartbeat.scheduleAtFixedRate(()->{try {if(!jobs.heartbeat(lease))alive.set(false);}catch(RuntimeException e){alive.set(false);com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"export.heartbeat_failed",e,job.id());}},15,15,TimeUnit.SECONDS);
            try {
                cleanup(job.id());
                UUID intermediate=jobs.beginArtifact(lease,"INTERMEDIATE",268435456L);
                if(intermediate==null)return true;
                var completed=new CompanySnapshot.Dataset[1];
                CompanySnapshot.Progress progress=rows->{
                    check.run();if(!jobs.checkpoint(lease,rows))throw new IOException("STALE_LEASE");
                };
                store.write(intermediate,268435456L,output->completed[0]=job.kind()==TransferJob.Kind.PERSONAL_EXPORT?
                    personalSnapshot.extract(job,output,progress):snapshot.extract(job,output,progress));
                check.run();var archive=new CompanyArchive(store);archive.validate(intermediate,completed[0],check);
                UUID download=jobs.beginArtifact(lease,"DOWNLOAD",67108864L);if(download==null)return true;
                var result=archive.packageSnapshot(intermediate,download,completed[0],check);
                check.run();boolean published=jobs.publish(lease,download,result.bytes(),result.sha256());
                LOG.atInfo().addKeyValue("event",published?"export.ready":"export.publish_rejected")
                    .addKeyValue("jobId",job.id().toString()).addKeyValue("fence",lease.fence())
                    .addKeyValue("durationMs",(System.nanoTime()-started)/1_000_000).log("Export publication finished");
            }catch(IOException | RuntimeException failure) {
                com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"export.attempt_failed",failure,job.id());
                var code=failure instanceof CompanySnapshot.ExportFailure export?export.code():
                    failure instanceof IOException && "ARTIFACT_LIMIT_EXCEEDED".equals(failure.getMessage())?
                        TransferJob.Failure.TRANSFER_LIMIT_EXCEEDED:TransferJob.Failure.WORK_FAILED;
                try {jobs.fail(lease,code);}catch(IllegalStateException stale){/* Recovery owns the newer attempt. */}
            }finally {
                heartbeat.shutdownNow();
                try {cleanup(job.id());}catch(IOException | RuntimeException e){com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"export.cleanup_failed",e,job.id());}
            }
        }
        return true;
    }
    @Scheduled(initialDelay=5000,fixedDelay=5000)
    public void scheduledExport() {
        try {runOnce();}catch(RuntimeException e) {
            com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"export.worker_failed",e,null);
        }
    }
}
