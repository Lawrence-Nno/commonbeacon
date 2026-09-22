package com.lawrencenno.commonbeacon.transfer.job;

import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.lawrencenno.commonbeacon.transfer.storage.ArtifactStore;
import static com.lawrencenno.commonbeacon.transfer.job.TransferJob.*;

/** No built-in export handler: feature stages supply the actual snapshot producer. */
public final class TransferWorker {
    @FunctionalInterface public interface ExportWriter {
        void write(TransferJob job,OutputStream output,Progress progress) throws IOException;
    }
    public interface Progress {void checkpoint(long offset) throws IOException;}
    private final TransferJobs jobs;
    private final ArtifactStore store;
    private final UUID worker=UUID.randomUUID();
    public TransferWorker(TransferJobs jobs,ArtifactStore store) {this.jobs=jobs;this.store=store;}
    public boolean runExportOnce(ExportWriter writer) {
        var claimed=jobs.claimExport(worker);
        if(claimed.isEmpty())return false;
        var job=claimed.get();var lease=job.lease();
        var alive=new AtomicBoolean(true);
        try(var heartbeat=Executors.newSingleThreadScheduledExecutor()) {
            heartbeat.scheduleAtFixedRate(() -> {
                try {if(!jobs.heartbeat(lease))alive.set(false);}catch(RuntimeException e){alive.set(false);}
            },15,15,TimeUnit.SECONDS);
            try {
                UUID key=jobs.beginArtifact(lease,"DOWNLOAD",67108864L);
                if(key==null)return true;
                store.write(key,67108864L,output -> writer.write(job,new FilterOutputStream(output) {
                    @Override public void write(int b) throws IOException {write(new byte[]{(byte)b},0,1);}
                    @Override public void write(byte[] b,int off,int len) throws IOException {
                        if(!alive.get() || Thread.currentThread().isInterrupted())throw new IOException("STALE_LEASE");
                        out.write(b,off,len);
                    }
                },offset -> {
                    if(!alive.get() || !jobs.checkpoint(lease,offset))throw new IOException("STALE_LEASE");
                }));
                if(!alive.get())return true;
                var artifact=store.inspect(key).orElseThrow(() -> new IOException("ARTIFACT_MISSING"));
                jobs.publish(lease,key,artifact.bytes(),artifact.sha256());
            } catch(IOException | RuntimeException failure) {
                try {jobs.fail(lease,Failure.WORK_FAILED);}catch(IllegalStateException stale) { /* fenced; recovery owns cleanup */ }
            } finally {heartbeat.shutdownNow();}
        }
        return true;
    }
}
