package com.lawrencenno.commonbeacon.transfer.storage;

import java.io.IOException;
import java.time.*;
import org.springframework.scheduling.annotation.Scheduled;
import com.lawrencenno.commonbeacon.transfer.job.TransferJobs;

public final class ArtifactReconciler {
    private final TransferJobs jobs;
    private final ArtifactStore store;
    public ArtifactReconciler(TransferJobs jobs,ArtifactStore store) {this.jobs=jobs;this.store=store;}
    public void reconcile() throws IOException {
        for(var artifact:jobs.artifacts()) {
            var stored=store.inspect(artifact.id());
            boolean exists=stored.isPresent() && (!artifact.state().equals("AVAILABLE")
                || (java.util.Objects.equals(artifact.bytes(),stored.get().bytes()) && java.util.Objects.equals(artifact.hash(),stored.get().sha256())));
            if(jobs.needsCleanup(artifact.id(),exists)) {store.delete(artifact.id());jobs.cleaned(artifact.id());}
        }
        var known=jobs.knownArtifactKeys();
        for(var key:store.keysOlderThan(Instant.now().minus(Duration.ofMinutes(15)))) {
            if(!known.contains(key))store.delete(key);
        }
        jobs.releaseCleanedReservations();
    }
    @Scheduled(initialDelay=0,fixedDelay=900000)
    public void scheduledReconciliation() {
        try {reconcile();}catch(IOException | RuntimeException e) {
            // Never include file paths, exception messages or payloads in operational logs.
            org.slf4j.LoggerFactory.getLogger(ArtifactReconciler.class).warn("Transfer artifact reconciliation needs retry");
        }
    }
}
