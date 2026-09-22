package com.lawrencenno.commonbeacon.transfer.access;

import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec;
import com.lawrencenno.commonbeacon.transfer.job.*;
import com.lawrencenno.commonbeacon.transfer.storage.ArtifactStore;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/admin/data/jobs","/api/v1/account/data/jobs"})
public class TransferController {
    public record Summary(UUID id,TransferJob.Kind kind,TransferJob.State state,long version,Instant createdAt,
        Instant updatedAt,Instant expiresAt,long processedRecords,TransferJob.Failure errorCode,boolean artifactAvailable,List<String> allowedActions) {}
    public record Page(List<Summary> items,String nextCursor) {}
    public record Cancellation(@NotNull @PositiveOrZero Long expectedVersion) {}
    public record TicketRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String recentAuthGrant) {
        @Override public String toString(){return "TicketRequest[redacted]";}
    }
    private final TransferJobs jobs;
    private final TransferAccess access;
    private final RecentAuthentication recent;
    private final ObjectProvider<ArtifactStore> stores;
    public TransferController(TransferJobs jobs,TransferAccess access,RecentAuthentication recent,ObjectProvider<ArtifactStore> stores) {
        this.jobs=jobs;this.access=access;this.recent=recent;this.stores=stores;
    }
    private boolean personal(HttpServletRequest r){return r.getServletPath().startsWith("/api/v1/account/");}
    static <T> T run(Supplier<T> action) {
        try{return action.get();}catch(IllegalStateException e){
            String code=Objects.toString(e.getMessage(),"");
            int status=switch(code){case "JOB_NOT_FOUND"->404;case "FORBIDDEN"->403;case "ARTIFACT_EXPIRED"->410;
                case "JOB_CONFLICT","IDEMPOTENCY_CONFLICT","DOWNLOAD_IN_PROGRESS","ACTIVE_JOB_EXISTS"->409;case "TRANSFER_QUOTA_EXCEEDED"->413;default->503;};
            if(status==503)code="TRANSFER_UNAVAILABLE";
            if(status==413)code="TRANSFER_LIMIT_EXCEEDED";
            if(code.equals("ACTIVE_JOB_EXISTS"))code="JOB_CONFLICT";
            throw new ApiFailure(status,code,status==404?"The transfer job was not found.":status==403?"You do not have permission for this action.":
                status==410?"The download is no longer available.":status==413?"The transfer exceeds the available capacity.":status==409?"The transfer changed. Reload it and try again.":"Transfer storage is unavailable. Try again later.");
        }
    }
    private Summary summary(TransferJob j) {
        boolean available=stores.getIfAvailable()!=null && run(()->jobs.artifactAvailable(j.requester(),j.id()));
        return summary(j,available);
    }
    static Summary summary(TransferJob j,boolean available) {
        var actions=new ArrayList<String>();
        if(!j.terminal() && j.state()!=TransferJob.State.COMMITTING)actions.add("CANCEL");
        if(available)actions.add("DOWNLOAD");
        return new Summary(j.id(),j.kind(),j.state(),j.version(),j.createdAt(),j.updatedAt(),j.expiresAt(),j.checkpoint(),j.errorCode(),available,List.copyOf(actions));
    }
    @GetMapping public Page list(Authentication authentication,HttpServletRequest request,
            @RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor) {
        boolean personal=personal(request);var actor=access.current(authentication,!personal);
        if(size<1 || size>100)throw new ApiFailure(400,"INVALID_REQUEST","Page size must be between 1 and 100.");
        Instant before=Instant.parse("9999-12-31T23:59:59Z");UUID beforeId=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        if(cursor!=null) {
            try {
                if(cursor.length()>128)throw new IllegalArgumentException();
                String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.US_ASCII).split("\\|",-1);
                if(parts.length!=2)throw new IllegalArgumentException();before=Instant.parse(parts[0]);beforeId=UUID.fromString(parts[1]);
                if(before.isBefore(Instant.parse("0001-01-01T00:00:00Z")) || before.isAfter(Instant.parse("9999-12-31T23:59:59Z")))throw new IllegalArgumentException();
            }catch(RuntimeException e){throw new ApiFailure(400,"INVALID_REQUEST","The page cursor is invalid.");}
        }
        Instant finalBefore=before;UUID finalId=beforeId;
        var rows=run(()->jobs.list(actor.id(),finalBefore,finalId,size,personal));boolean more=rows.size()>size;
        var page=rows.subList(0,Math.min(size,rows.size()));String next=null;
        if(more){var last=page.getLast();next=Base64.getUrlEncoder().withoutPadding().encodeToString((last.createdAt()+"|"+last.id()).getBytes(StandardCharsets.US_ASCII));}
        return new Page(page.stream().map(this::summary).toList(),next);
    }
    @GetMapping("/{id}") public Summary status(Authentication authentication,HttpServletRequest request,@PathVariable UUID id) {
        boolean personal=personal(request);var actor=access.current(authentication,!personal);
        return summary(run(()->jobs.status(actor.id(),id,personal)));
    }
    @PostMapping("/{id}/cancel") public Summary cancel(Authentication authentication,HttpServletRequest request,@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Cancellation body) {
        boolean personal=personal(request);var actor=access.current(authentication,!personal);
        return summary(run(()->jobs.cancel(actor.id(),id,body.expectedVersion(),key,personal)));
    }
    private ArtifactStore store(){var store=stores.getIfAvailable();if(store==null)throw new ApiFailure(503,"TRANSFER_UNAVAILABLE","Transfer storage is unavailable.");return store;}
    @PostMapping("/{id}/download-ticket") public RecentAuthentication.Issued ticket(Authentication authentication,HttpServletRequest request,
            @PathVariable UUID id,@Valid @RequestBody TicketRequest body) {
        boolean personal=personal(request);var actor=access.current(authentication,!personal);
        run(()->jobs.status(actor.id(),id,personal));run(()->jobs.downloadInfo(actor.id(),id));store();
        return recent.ticket(actor,request.getSession(),id,body.recentAuthGrant());
    }
    @GetMapping("/{id}/download") public void download(Authentication authentication,HttpServletRequest request,HttpServletResponse response,
            @PathVariable UUID id,@RequestHeader("X-Download-Ticket") String token) throws IOException {
        boolean personal=personal(request);var actor=access.current(authentication,!personal);
        run(()->jobs.status(actor.id(),id,personal));var store=store();var session=request.getSession();String sessionId=session.getId();
        recent.consumeTicket(actor,session,id,token);
        var file=run(()->jobs.beginDownload(actor.id(),id));boolean delivered=false;
        try {
            var actual=store.inspect(file.artifact());
            if(actual.isEmpty() || actual.get().bytes()!=file.bytes() || !actual.get().sha256().equals(file.hash()))
                throw new ApiFailure(410,"ARTIFACT_EXPIRED","The download is no longer available.");
            response.setContentType("application/zip");response.setContentLengthLong(file.bytes());
            response.setHeader("Content-Disposition","attachment; filename=\"commonbeacon-"+id+".zip\"");
            var digest=ArchiveCodec.sha256();long count=0;
            try(var input=store.open(file.artifact())) {
                byte[] buffer=new byte[65536];int n;
                while((n=input.read(buffer))!=-1) {
                    var current=access.current(authentication,!personal);
                    if(!current.id().equals(actor.id()) || current.revision()!=actor.revision()
                        || !recent.sessionAlive(session,sessionId) || !jobs.downloadLive(file.lease()))throw new ApiFailure(403,"FORBIDDEN","Download authorization expired.");
                    count+=n;if(count>file.bytes())throw new IOException("ARTIFACT_CHANGED");
                    digest.update(buffer,0,n);response.getOutputStream().write(buffer,0,n);
                }
                if(count!=file.bytes() || !HexFormat.of().formatHex(digest.digest()).equals(file.hash()))throw new IOException("ARTIFACT_CHANGED");
                response.getOutputStream().flush();delivered=true;
            }
        } catch(ApiFailure | IOException failure) {
            if(!response.isCommitted()) {
                response.reset();response.setHeader("Cache-Control","no-store");
                if(failure instanceof ApiFailure api)throw api;
                throw new ApiFailure(503,"TRANSFER_UNAVAILABLE","Transfer storage is unavailable.");
            }
            // Headers/body already sent: close an incomplete stream, never append a JSON error to ZIP bytes.
        } finally {jobs.finishDownload(actor.id(),id,file.lease(),delivered);}
    }
}
