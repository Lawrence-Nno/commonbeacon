package com.lawrencenno.commonbeacon.transfer.inspection;

import com.lawrencenno.commonbeacon.transfer.archive.*;
import com.lawrencenno.commonbeacon.transfer.job.*;
import com.lawrencenno.commonbeacon.transfer.storage.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

public final class ImportInspector {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ImportInspector.class);
    private final TransferJobs jobs;private final ArtifactStore store;
    public ImportInspector(TransferJobs jobs,ArtifactStore store){this.jobs=jobs;this.store=store;}
    public boolean runOnce() {
        var claimed=jobs.claimInspection(UUID.randomUUID());if(claimed.isEmpty())return false;
        var job=claimed.get();long deadline=System.nanoTime()+600_000_000_000L;
        long[] last={0},rows={0};
        QuarantineZip.Check check=()->{
            long now=System.nanoTime();if(now>deadline || Thread.currentThread().isInterrupted())throw new IOException("INSPECTION_INTERRUPTED");
            if(now-last[0]>1_000_000_000L) {
                if(!jobs.heartbeat(job.lease()) || !jobs.checkpoint(job.lease(),rows[0]))throw new IOException("INSPECTION_INTERRUPTED");
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
                result=ImportArchives.inspect(job.provider(),channel,check,row->{rows[0]++;});
            } catch(QuarantineZip.Rejected e) {result=new ArchiveFormat.Result(null,List.of(new ArchiveFormat.Issue("archive",0,e.code)),1,rows[0]);}
              catch(java.util.zip.ZipException | EOFException e) {result=new ArchiveFormat.Result(null,List.of(new ArchiveFormat.Issue("archive",0,"INVALID_ZIP")),1,rows[0]);}
            check.run();jobs.finishInspection(job.lease(),source,result.valid(),result.rows(),result.totalErrors(),JsonMapper.builder().build().writeValueAsString(result.issues()));
            LOG.atInfo().addKeyValue("event","import.inspected").addKeyValue("jobId",job.id().toString()).addKeyValue("valid",result.valid()).log("Quarantine inspection finished");
        } catch(IOException | RuntimeException e) {
            com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"import.inspection_failed",e,job.id());
            try{jobs.fail(job.lease(),TransferJob.Failure.WORK_FAILED);}catch(IllegalStateException ignored){/* Cancellation/recovery owns the job. */}
        }
        return true;
    }
    @Scheduled(initialDelay=5000,fixedDelay=5000)
    public void scheduledInspection() {
        try{runOnce();}catch(RuntimeException e){com.lawrencenno.commonbeacon.shared.OperationalLogs.failure(LOG,"import.worker_failed",e,null);}
    }
}
