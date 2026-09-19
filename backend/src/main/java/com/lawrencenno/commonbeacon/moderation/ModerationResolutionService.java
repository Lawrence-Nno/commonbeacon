package com.lawrencenno.commonbeacon.moderation;

import java.util.UUID;
import jakarta.validation.Valid;
import com.lawrencenno.commonbeacon.board.BoardRepository;
import com.lawrencenno.commonbeacon.identity.IdentityService;
import com.lawrencenno.commonbeacon.question.QuestionRepository;
import com.lawrencenno.commonbeacon.reply.ReplyRepository;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMINISTRATOR')")
public class ModerationResolutionService {
    private final ReportRepository reports;
    private final BoardRepository boards;
    private final QuestionRepository questions;
    private final ReplyRepository replies;
    private final IdentityService identity;
    private final ModerationActionRepository actions;
    private final ModerationReadService reads;

    public ModerationResolutionService(ReportRepository reports, BoardRepository boards, QuestionRepository questions,
            ReplyRepository replies, IdentityService identity, ModerationActionRepository actions, ModerationReadService reads) {
        this.reports = reports; this.boards = boards; this.questions = questions; this.replies = replies;
        this.identity = identity; this.actions = actions; this.reads = reads;
    }

    @Transactional
    public ModerationReportDetail resolve(UUID id, @Valid ResolveReportRequest request, Authentication authentication) {
        // Locate using scalar projections only: managed state must not predate a lock wait.
        var target = reports.findTarget(id).orElseThrow(ModerationResolutionService::reportMissing);
        var replyId = target.getReplyId();
        if ((replyId != null) != (request.expectedQuestionVersion() != null))
            throw new ApiFailure(400, "INVALID_REQUEST", "Include expectedQuestionVersion only for reply reports.");
        var questionId = replyId == null ? target.getQuestionId()
                : replies.findQuestionId(replyId).orElseThrow(ModerationResolutionService::contentMissing);
        var boardId = questions.findBoardId(questionId).orElseThrow(ModerationResolutionService::contentMissing);
        boards.findForUpdate(boardId).orElseThrow(ModerationResolutionService::contentMissing);
        var question = questions.findForUpdate(questionId).orElseThrow(ModerationResolutionService::contentMissing);
        var reply = replyId == null ? null : replies.findForUpdate(replyId, questionId).orElseThrow(ModerationResolutionService::contentMissing);
        var report = reports.findForUpdate(id).orElseThrow(ModerationResolutionService::reportMissing);
        var actorId = identity.current(authentication).id();
        if (report.getStatus() != ContentReport.Status.OPEN)
            throw new ApiFailure(409, "REPORT_ALREADY_RESOLVED", "This report was already resolved. Reload to review the result.");
        long targetVersion = reply == null ? question.getVersion() : reply.getVersion();
        if (report.getVersion() != request.expectedVersion() || targetVersion != request.expectedTargetVersion()
                || (reply != null && question.getVersion() != request.expectedQuestionVersion()))
            throw new ApiFailure(409, "STALE_EDIT", "The report or content changed. Reload and review before trying again.");
        boolean visible = reply == null ? question.isVisible() : reply.isVisible();
        if ((request.decision() == ResolveReportRequest.Decision.HIDE && !visible)
                || (request.decision() == ResolveReportRequest.Decision.ACKNOWLEDGE_HIDDEN && visible))
            throw new ApiFailure(409, "MODERATION_STATE_CONFLICT", "This decision does not match the target's current visibility. Reload and review it.");
        if (request.decision() == ResolveReportRequest.Decision.HIDE) {
            if (reply == null) question.hide();
            else { reply.hide(); question.clearAcceptanceOf(replyId); }
            actions.appendHide(actorId, report.getQuestionId(), report.getReplyId(), request.resolutionNote());
        }
        report.resolve(actorId, request);
        // Flush all versioned entities before the JDBC read of the confirmed result.
        reports.flush();
        return reads.get(id);
    }
    private static ApiFailure reportMissing() { return new ApiFailure(404, "REPORT_NOT_FOUND", "This report could not be found."); }
    private static ApiFailure contentMissing() { return new ApiFailure(404, "CONTENT_NOT_FOUND", "This content could not be found."); }
}
