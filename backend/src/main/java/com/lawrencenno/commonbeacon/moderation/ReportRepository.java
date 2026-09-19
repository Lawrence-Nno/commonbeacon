package com.lawrencenno.commonbeacon.moderation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<ContentReport, UUID> {
    boolean existsByReporterIdAndQuestionIdAndStatus(UUID reporterId, UUID questionId, ContentReport.Status status);
    boolean existsByReporterIdAndReplyIdAndStatus(UUID reporterId, UUID replyId, ContentReport.Status status);
}
