package com.lawrencenno.commonbeacon.transfer.access;

import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.transfer.job.TransferJobs;
import com.lawrencenno.commonbeacon.transfer.storage.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/account/data/exports")
public class PersonalExportController {
    public record Request(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String recentAuthGrant) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String name,Object value) {throw new IllegalArgumentException("UNKNOWN_FIELD");}
        @Override public String toString(){return "PersonalExportRequest[redacted]";}
    }
    private final TransferJobs jobs;
    private final TransferAccess access;
    private final RecentAuthentication recent;
    private final ObjectProvider<ArtifactStore> stores;
    public PersonalExportController(TransferJobs jobs,TransferAccess access,RecentAuthentication recent,ObjectProvider<ArtifactStore> stores) {
        this.jobs=jobs;this.access=access;this.recent=recent;this.stores=stores;
    }
    @PostMapping
    public ResponseEntity<TransferController.Summary> create(Authentication authentication,HttpServletRequest request,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Request body) {
        var actor=access.current(authentication,false);
        if(stores.getIfAvailable()==null)throw new ApiFailure(503,"TRANSFER_UNAVAILABLE","Transfer storage is unavailable.");
        var job=TransferController.run(()->jobs.createPersonalExport(actor.id(),key,
            ()->recent.consume(access.current(authentication,false),request.getSession(),RecentAuthentication.Scope.PERSONAL_EXPORT,body.recentAuthGrant())));
        return ResponseEntity.accepted().body(TransferController.summary(job,
            TransferController.run(()->jobs.artifactAvailable(actor.id(),job.id()))));
    }
}
