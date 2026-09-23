package com.lawrencenno.commonbeacon.transfer.access;

import java.util.UUID;
import com.lawrencenno.commonbeacon.transfer.job.ImportReconciliation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/admin/data/imports")
public class ImportReconciliationController {
    private final TransferAccess access;private final ImportReconciliation reconciliation;
    public ImportReconciliationController(TransferAccess access,ImportReconciliation reconciliation){this.access=access;this.reconciliation=reconciliation;}
    @GetMapping("/{id}/reconciliation")
    public JsonNode report(Authentication authentication,@PathVariable UUID id,@RequestParam(required=false) String cursor) {
        var actor=access.current(authentication,true);return TransferController.run(()->reconciliation.report(actor.id(),id,cursor));
    }
}
