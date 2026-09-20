package com.lawrencenno.commonbeacon.moderation;

import java.util.Map;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/moderation/summary")
public class OperationalSummaryController {
    private final OperationalSummaryService summaries;
    public OperationalSummaryController(OperationalSummaryService summaries) { this.summaries = summaries; }

    @GetMapping
    public ResponseEntity<OperationalSummary> get(@RequestParam Map<String, String> parameters) {
        if (!parameters.isEmpty()) throw new ApiFailure(400, "INVALID_REQUEST", "The operational summary does not accept filters.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summaries.get());
    }
}
