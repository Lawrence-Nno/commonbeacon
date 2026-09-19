package com.lawrencenno.commonbeacon.moderation;

import com.lawrencenno.commonbeacon.board.BoardRepository;
import com.lawrencenno.commonbeacon.identity.IdentityService;
import com.lawrencenno.commonbeacon.question.QuestionRepository;
import com.lawrencenno.commonbeacon.reply.ReplyRepository;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import jakarta.validation.Valid;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class ReportService {
    private final ReportRepository reports;
    private final BoardRepository boards;
    private final QuestionRepository questions;
    private final ReplyRepository replies;
    private final IdentityService identity;

    public ReportService(ReportRepository reports, BoardRepository boards, QuestionRepository questions,
                         ReplyRepository replies, IdentityService identity) {
        this.reports = reports; this.boards = boards; this.questions = questions;
        this.replies = replies; this.identity = identity;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ReportReceipt create(@Valid CreateReportRequest request, Authentication authentication) {
        if ((request.questionId() == null) == (request.replyId() == null))
            throw new ApiFailure(400, "INVALID_REQUEST", "Choose exactly one question or reply to report.");
        var reporter = identity.current(authentication).id();
        // Scalar lookups first; never preload managed target state before waiting for its lock.
        var questionId = request.questionId() != null ? request.questionId()
                : replies.findVisibleQuestionId(request.replyId()).orElseThrow(ReportService::missing);
        var boardId = questions.findVisibleBoardId(questionId).orElseThrow(ReportService::missing);
        boards.findForUpdate(boardId).orElseThrow(ReportService::missing);
        questions.findVisibleForUpdate(questionId).orElseThrow(ReportService::missing);
        if (request.replyId() != null)
            replies.findVisibleForUpdate(request.replyId(), questionId).orElseThrow(ReportService::missing);
        // Archived boards remain reportable; reporting never modifies content or its version.
        boolean duplicate = request.questionId() != null
                ? reports.existsByReporterIdAndQuestionIdAndStatus(reporter, request.questionId(), ContentReport.Status.OPEN)
                : reports.existsByReporterIdAndReplyIdAndStatus(reporter, request.replyId(), ContentReport.Status.OPEN);
        if (duplicate) throw duplicate();
        try {
            return ReportReceipt.from(reports.saveAndFlush(ContentReport.create(reporter, request)));
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                        && ("ux_report_open_question".equals(constraint.getConstraintName())
                        || "ux_report_open_reply".equals(constraint.getConstraintName()))) throw duplicate();
            }
            throw failure;
        }
    }
    private static ApiFailure missing() {
        return new ApiFailure(404, "CONTENT_NOT_FOUND", "This content is not available to report.");
    }
    private static ApiFailure duplicate() {
        return new ApiFailure(409, "REPORT_ALREADY_OPEN", "You already have an open report for this content.");
    }
}
