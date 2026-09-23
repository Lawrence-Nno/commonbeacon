package com.lawrencenno.commonbeacon.transfer.access;

import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.transfer.job.*;
import com.lawrencenno.commonbeacon.transfer.storage.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/admin/data/imports")
public class ImportUploadController {
    public record Request(@NotNull @Min(1) @Max(1) Integer formatVersion,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String recentAuthGrant, TransferJob.Provider provider) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("UNKNOWN_FIELD");}
        @Override public String toString(){return "ImportRequest[redacted]";}
    }
    public record Inspection(UUID jobId,String archiveSha256,boolean valid,long rowsChecked,long totalErrors,JsonNode issues,java.time.Instant inspectedAt,boolean activationAvailable) {}
    private final TransferJobs jobs;private final TransferAccess access;private final RecentAuthentication recent;private final ObjectProvider<ArtifactStore> stores;
    public ImportUploadController(TransferJobs jobs,TransferAccess access,RecentAuthentication recent,ObjectProvider<ArtifactStore> stores){this.jobs=jobs;this.access=access;this.recent=recent;this.stores=stores;}
    private ArtifactStore store(){var store=stores.getIfAvailable();if(store==null)throw new ApiFailure(503,"TRANSFER_UNAVAILABLE","Transfer storage is unavailable.");return store;}
    @PostMapping
    public ResponseEntity<TransferController.Summary> create(Authentication authentication,HttpServletRequest request,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Request body) {
        var actor=access.current(authentication,true);store();
        var job=TransferController.run(()->jobs.createImport(actor.id(),key,body.provider()==null?TransferJob.Provider.NATIVE:body.provider(),()->recent.consume(access.current(authentication,true),request.getSession(),RecentAuthentication.Scope.IMPORT_UPLOAD,body.recentAuthGrant())));
        return ResponseEntity.status(201).body(TransferController.summary(job,false));
    }
    @PutMapping(value="/{id}/archive",consumes={"application/zip","application/json"})
    public TransferController.Summary upload(Authentication authentication,HttpServletRequest request,@PathVariable UUID id)throws IOException {
        var actor=access.current(authentication,true);var store=store();
        var current=TransferController.run(()->jobs.status(actor.id(),id));
        String expected=current.provider()==TransferJob.Provider.DISCOURSE?"application/json":"application/zip";
        if(request.getContentType()==null || !org.springframework.http.MediaType.parseMediaType(request.getContentType()).isCompatibleWith(org.springframework.http.MediaType.parseMediaType(expected)))
            throw new ApiFailure(415,"UNSUPPORTED_MEDIA_TYPE","Upload the format selected for this import.");
        long limit=current.provider()==TransferJob.Provider.DISCOURSE?8388608L:67108864L;
        String limitMessage=current.provider()==TransferJob.Provider.DISCOURSE?"Discourse uploads are limited to 8 MiB.":"Archive uploads are limited to 64 MiB.";
        if(request.getContentLengthLong()>limit)throw new ApiFailure(413,"TRANSFER_LIMIT_EXCEEDED",limitMessage);
        var upload=TransferController.run(()->jobs.beginUpload(actor.id(),id));
        var live=new AtomicBoolean(true);long deadline=System.nanoTime()+600_000_000_000L;boolean complete=false;
        try(var heartbeat=Executors.newSingleThreadScheduledExecutor()) {
            heartbeat.scheduleAtFixedRate(()->{try{if(System.nanoTime()>deadline || !jobs.heartbeat(upload.lease()))live.set(false);}catch(RuntimeException e){live.set(false);}},15,15,TimeUnit.SECONDS);
            try {
                var saved=store.write(upload.artifact(),limit,out->{
                    byte[] buffer=new byte[65536];long bytes=0;int n;
                    try(var input=request.getInputStream()) {
                        while((n=input.read(buffer))!=-1) {
                            if(!live.get() || System.nanoTime()>deadline || request.getSession(false)==null)throw new IOException("UPLOAD_INTERRUPTED");
                            access.current(authentication,true);if(!jobs.heartbeat(upload.lease()))throw new IOException("UPLOAD_INTERRUPTED");
                            bytes+=n;out.write(buffer,0,n);
                        }
                    }
                    if(bytes==0 || (request.getContentLengthLong()>=0 && request.getContentLengthLong()!=bytes))throw new IOException("UPLOAD_INTERRUPTED");
                });
                if(!live.get() || System.nanoTime()>deadline || request.getSession(false)==null)throw new IOException("UPLOAD_INTERRUPTED");
                access.current(authentication,true);
                var job=TransferController.run(()->jobs.finishUpload(upload,saved.bytes(),saved.sha256()));complete=true;
                return TransferController.summary(job,false);
            } finally {heartbeat.shutdownNow();}
        } catch(IOException e) {
            if("ARTIFACT_LIMIT_EXCEEDED".equals(e.getMessage()))throw new ApiFailure(413,"TRANSFER_LIMIT_EXCEEDED",limitMessage);
            if("ARTIFACT_QUOTA_EXCEEDED".equals(e.getMessage()))throw new ApiFailure(503,"TRANSFER_UNAVAILABLE","Private transfer storage has insufficient free capacity. Try again later.");
            throw new ApiFailure(400,"UPLOAD_INTERRUPTED","The upload did not complete. Restart the entire archive upload.");
        } finally {
            if(!complete) {
                jobs.abortUpload(upload);
                // Store deletion runs outside database locks; publication of an incomplete upload is impossible.
                if(jobs.needsCleanup(upload.artifact(),false)) {
                    try{store.delete(upload.artifact());jobs.cleaned(upload.artifact());}catch(IOException ignored){/* Scheduled cleanup retries. */}
                }
            }
        }
    }
    @GetMapping("/{id}/inspection")
    public Inspection inspection(Authentication authentication,@PathVariable UUID id) {
        var actor=access.current(authentication,true);var result=TransferController.run(()->jobs.inspection(actor.id(),id));
        return new Inspection(result.jobId(),result.archiveSha256(),result.valid(),result.rowsChecked(),result.totalErrors(),JsonMapper.builder().build().readTree(result.issues()),result.inspectedAt(),false);
    }
}
