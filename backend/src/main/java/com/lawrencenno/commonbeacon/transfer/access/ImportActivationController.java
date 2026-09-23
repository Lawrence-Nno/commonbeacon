package com.lawrencenno.commonbeacon.transfer.access;

import com.lawrencenno.commonbeacon.transfer.job.ImportActivation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/data/imports")
public class ImportActivationController {
    public record Request(@NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String reviewDigest,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String archiveDigest,
            @NotNull @PositiveOrZero Long targetGeneration,
            @NotNull @AssertTrue Boolean acknowledgedPrivateContent,
            @NotNull @AssertTrue Boolean acknowledgedInactiveAuthors,
            @NotNull @Size(max=32) Set<@NotBlank @Size(max=80) String> acknowledgedWarnings,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String recentAuthGrant) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("UNKNOWN_FIELD");}
        @Override public String toString(){return "ActivationRequest[redacted]";}
    }
    private final ImportActivation activation;private final TransferAccess access;private final RecentAuthentication recent;
    public ImportActivationController(ImportActivation activation,TransferAccess access,RecentAuthentication recent){this.activation=activation;this.access=access;this.recent=recent;}
    @PostMapping("/{id}/confirm")
    public ResponseEntity<TransferController.Summary> confirm(Authentication authentication,HttpServletRequest request,
            @PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Request body) {
        var actor=access.current(authentication,true);
        var confirmation=new ImportActivation.Confirmation(body.expectedVersion(),body.reviewDigest(),body.archiveDigest(),body.targetGeneration(),
            body.acknowledgedPrivateContent(),body.acknowledgedInactiveAuthors(),body.acknowledgedWarnings());
        var job=TransferController.run(()->activation.confirm(actor.id(),id,key,confirmation,()->{
            var current=access.current(authentication,true);
            recent.consume(current,request.getSession(),RecentAuthentication.Scope.IMPORT_COMMIT,body.recentAuthGrant());
        }));
        return ResponseEntity.accepted().body(TransferController.summary(job,false));
    }
}
