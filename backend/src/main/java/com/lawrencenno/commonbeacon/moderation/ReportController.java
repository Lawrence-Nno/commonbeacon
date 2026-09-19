package com.lawrencenno.commonbeacon.moderation;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports) { this.reports = reports; }
    @PostMapping
    public ResponseEntity<ReportReceipt> create(@Valid @RequestBody CreateReportRequest request,
                                                Authentication authentication) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(reports.create(request, authentication));
    }
}
