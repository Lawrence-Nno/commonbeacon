package com.lawrencenno.commonbeacon.moderation;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.shared.PageResponse;

@RestController
@RequestMapping("/api/v1/moderation/reports")
public class ModerationController {
    private final ModerationReadService reports;
    private final ModerationResolutionService resolutions;
    public ModerationController(ModerationReadService reports, ModerationResolutionService resolutions) {
        this.reports = reports; this.resolutions = resolutions;
    }
    @PostMapping("/{id}/resolve")
    public ResponseEntity<ModerationReportDetail> resolve(@PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody ResolveReportRequest request,
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(resolutions.resolve(id, request, authentication));
    }
    @GetMapping
    public ResponseEntity<PageResponse<ModerationReport>> list(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam Map<String, String> parameters) {
        if (!Set.of("status", "page", "size").containsAll(parameters.keySet()))
            throw new ApiFailure(400, "INVALID_REQUEST", "Only status, page, and size are supported.");
        if (parameters.containsKey("status") && parameters.get("status").isBlank())
            throw new ApiFailure(400, "INVALID_STATUS", "Use OPEN or RESOLVED.");
        if ((parameters.containsKey("page") && parameters.get("page").isBlank())
                || (parameters.containsKey("size") && parameters.get("size").isBlank()))
            throw new ApiFailure(400, "INVALID_PAGE", "Page and size must be integers.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.list(status, page, size));
    }
    @GetMapping("/{id}")
    public ResponseEntity<ModerationReportDetail> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.get(id));
    }
}
