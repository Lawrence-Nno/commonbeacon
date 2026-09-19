package com.lawrencenno.commonbeacon.moderation;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "content_report")
public class ContentReport {
    @Id private UUID id;
    @Column(name = "reporter_id", nullable = false) private UUID reporterId;
    @Column(name = "question_id") private UUID questionId;
    @Column(name = "reply_id") private UUID replyId;
    @Column(nullable = false, columnDefinition = "text") private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
    @Column(name = "resolver_id") private UUID resolverId;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "resolution_decision", length = 30) private String resolutionDecision;
    @Column(name = "resolution_note", columnDefinition = "text") private String resolutionNote;

    public enum Status { OPEN, RESOLVED }
    protected ContentReport() {}
    static ContentReport create(UUID reporterId, CreateReportRequest request) {
        var report = new ContentReport();
        report.id = UUID.randomUUID();
        report.reporterId = reporterId;
        report.questionId = request.questionId();
        report.replyId = request.replyId();
        report.reason = request.reason();
        report.status = Status.OPEN;
        report.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        report.updatedAt = report.createdAt;
        return report;
    }
    public UUID getId() { return id; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
