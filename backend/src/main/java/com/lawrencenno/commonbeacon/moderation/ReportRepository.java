package com.lawrencenno.commonbeacon.moderation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<ContentReport, UUID> {
    interface Target { UUID getQuestionId(); UUID getReplyId(); }
    @org.springframework.data.jpa.repository.Query("select r.questionId as questionId, r.replyId as replyId from ContentReport r where r.id = :id")
    java.util.Optional<Target> findTarget(@org.springframework.data.repository.query.Param("id") UUID id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from ContentReport r where r.id = :id")
    java.util.Optional<ContentReport> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    boolean existsByReporterIdAndQuestionIdAndStatus(UUID reporterId, UUID questionId, ContentReport.Status status);
    boolean existsByReporterIdAndReplyIdAndStatus(UUID reporterId, UUID replyId, ContentReport.Status status);
}
