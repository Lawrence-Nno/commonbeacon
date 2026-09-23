package com.lawrencenno.commonbeacon.transfer.inspection;

import com.lawrencenno.commonbeacon.transfer.archive.*;
import com.lawrencenno.commonbeacon.transfer.job.*;
import com.lawrencenno.commonbeacon.transfer.storage.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

public final class ImportDryRunWorker {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ImportDryRunWorker.class);
    private final TransferJobs jobs;private final ArtifactStore store;private final ImportDryRuns dryRuns;
    public ImportDryRunWorker(TransferJobs jobs,ArtifactStore store,ImportDryRuns dryRuns){this.jobs=jobs;this.store=store;this.dryRuns=dryRuns;}
    public boolean runOnce() {
        var claimed=dryRuns.claim(UUID.randomUUID());if(claimed.isEmpty())return false;
        var job=claimed.get();long deadline=System.nanoTime()+600_000_000_000L;
        long[] last={0},rows={0};
        QuarantineZip.Check check=()->{
            long now=System.nanoTime();if(now>deadline || Thread.currentThread().isInterrupted())throw new IOException("INSPECTION_INTERRUPTED");
            if(now-last[0]>1_000_000_000L) {
                if(!jobs.heartbeat(job.lease()))throw new IOException("INSPECTION_INTERRUPTED");
                last[0]=now;
            }
        };
        try {
            var source=jobs.inspectionSource(job.lease());ArchiveFormat.Result result;
            try(var channel=store.openChannel(source.id())) {
                var digest=ArchiveCodec.sha256();var buffer=ByteBuffer.allocate(65536);long count=0;
                while(true) {check.run();buffer.clear();int n=channel.read(buffer);if(n<0)break;count+=n;
                    if(count>67108864)throw new QuarantineZip.Rejected("ARCHIVE_SIZE_LIMIT");digest.update(buffer.array(),0,n);}
                if(count!=source.bytes() || !HexFormat.of().formatHex(digest.digest()).equals(source.hash()))throw new QuarantineZip.Rejected("ARCHIVE_DIGEST_MISMATCH");
                var batch=new ArrayList<ArchiveFormat.Row>();long[] batchBytes={0};
                result=ImportArchives.inspect(job.provider(),channel,check,row->{
                    rows[0]++;int size=row.data().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if(!batch.isEmpty() && (batch.size()>=64 || batchBytes[0]+size>1048576)){dryRuns.stage(job.lease(),batch);batch.clear();batchBytes[0]=0;}
                    batch.add(row);batchBytes[0]+=size;
                });
                if(!batch.isEmpty())dryRuns.stage(job.lease(),batch);
            } catch(QuarantineZip.Rejected e) {result=new ArchiveFormat.Result(null,List.of(new ArchiveFormat.Issue("archive",0,e.code)),1,rows[0]);}
              catch(java.util.zip.ZipException | EOFException e) {result=new ArchiveFormat.Result(null,List.of(new ArchiveFormat.Issue("archive",0,"INVALID_ZIP")),1,rows[0]);}
            check.run();dryRuns.finish(job.lease(),source,result);
            LOG.atInfo().addKeyValue("event","import.reviewed").addKeyValue("jobId",job.id().toString()).addKeyValue("valid",result.valid()).log("Import dry run finished");
        } catch(IOException | RuntimeException e) {
            com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"import.dry_run_failed",e,job.id());
            try{jobs.fail(job.lease(),TransferJob.Failure.WORK_FAILED);}catch(IllegalStateException ignored){/* Cancellation/recovery owns the job. */}
        }
        return true;
    }
    @Scheduled(initialDelay=5000,fixedDelay=5000)
    public void scheduledDryRun() {
        try{runOnce();}catch(RuntimeException e){com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"import.worker_failed",e,null);}
    }
}
