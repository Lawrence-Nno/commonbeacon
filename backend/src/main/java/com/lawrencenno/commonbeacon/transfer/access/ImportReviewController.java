package com.lawrencenno.commonbeacon.transfer.access;

import com.lawrencenno.commonbeacon.transfer.job.ImportDryRuns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/admin/data/imports")
public class ImportReviewController {
    public record Request(@NotNull @PositiveOrZero Long expectedVersion) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("UNKNOWN_FIELD");}
    }
    private final ImportDryRuns dryRuns;private final TransferAccess access;
    public ImportReviewController(ImportDryRuns dryRuns,TransferAccess access){this.dryRuns=dryRuns;this.access=access;}
    @PostMapping("/{id}/dry-run")
    public ResponseEntity<TransferController.Summary> request(Authentication authentication,@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Request body) {
        var actor=access.current(authentication,true);
        var job=TransferController.run(()->dryRuns.request(actor.id(),id,body.expectedVersion(),key));
        return ResponseEntity.accepted().body(TransferController.summary(job,false));
    }
    @GetMapping("/{id}/review")
    public JsonNode review(Authentication authentication,@PathVariable UUID id) {
        var actor=access.current(authentication,true);return TransferController.run(()->dryRuns.review(actor.id(),id));
    }
}
