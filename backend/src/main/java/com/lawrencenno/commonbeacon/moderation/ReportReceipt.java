package com.lawrencenno.commonbeacon.moderation;

import java.time.Instant;
import java.util.UUID;

public record ReportReceipt(UUID id, ContentReport.Status status, Instant createdAt) {
    static ReportReceipt from(ContentReport report) {
        return new ReportReceipt(report.getId(), report.getStatus(), report.getCreatedAt());
    }
}
