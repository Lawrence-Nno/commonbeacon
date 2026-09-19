package com.lawrencenno.commonbeacon.moderation;

import java.util.UUID;
import jakarta.validation.Valid;
import com.lawrencenno.commonbeacon.board.BoardRepository;
import com.lawrencenno.commonbeacon.identity.IdentityService;
import com.lawrencenno.commonbeacon.question.QuestionRepository;
import com.lawrencenno.commonbeacon.reply.ReplyRepository;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.shared.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMINISTRATOR')")
public class ModerationContentService {
    private final BoardRepository boards;
    private final QuestionRepository questions;
    private final ReplyRepository replies;
    private final IdentityService identity;
    private final ModerationActionRepository actions;
    private final ModerationReadRepository reads;
    public ModerationContentService(BoardRepository boards, QuestionRepository questions, ReplyRepository replies,
            IdentityService identity, ModerationActionRepository actions, ModerationReadRepository reads) {
        this.boards = boards; this.questions = questions; this.replies = replies;
        this.identity = identity; this.actions = actions; this.reads = reads;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModerationContext get(UUID id, boolean reply) {
        return context(id, reply);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ModerationAction> history(UUID id, boolean reply, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new ApiFailure(400, "INVALID_PAGE", "Use a nonnegative page and a size from 1 to 100; the offset must fit a 32-bit integer.");
        context(id, reply);
        long total = actions.count(id, reply);
        return new PageResponse<>(actions.list(id, reply, size, (long) page * size), page, size, total,
                (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size));
    }
    @Transactional
    public ModerationContext restore(UUID id, boolean replyTarget, @Valid RestoreContentRequest request, Authentication authentication) {
        if (replyTarget != (request.expectedQuestionVersion() != null))
            throw new ApiFailure(400, "INVALID_REQUEST", "Include expectedQuestionVersion only for reply restoration.");
        var questionId = questionId(id, replyTarget);
        var boardId = questions.findBoardId(questionId).orElseThrow(ModerationContentService::missing);
        boards.findForUpdate(boardId).orElseThrow(ModerationContentService::missing);
        var question = questions.findForUpdate(questionId).orElseThrow(ModerationContentService::missing);
        var reply = replyTarget ? replies.findForUpdate(id, questionId).orElseThrow(ModerationContentService::missing) : null;
        if ((reply == null ? question.getVersion() : reply.getVersion()) != request.expectedTargetVersion()
                || (reply != null && question.getVersion() != request.expectedQuestionVersion()))
            throw new ApiFailure(409, "STALE_EDIT", "The content changed. Reload and review before restoring it.");
        if (reply == null ? question.isVisible() : reply.isVisible())
            throw new ApiFailure(409, "MODERATION_STATE_CONFLICT", "This content is already visible. Reload and review it.");
        if (reply == null) question.restore(); else reply.restore();
        actions.appendRestore(identity.current(authentication).id(), replyTarget ? null : id, replyTarget ? id : null, request.reason());
        questions.flush();
        return context(id, replyTarget);
    }
    private UUID questionId(UUID id, boolean reply) {
        return reply ? replies.findQuestionId(id).orElseThrow(ModerationContentService::missing) : id;
    }
    private ModerationContext context(UUID id, boolean reply) {
        return reads.context(questionId(id, reply), reply ? id : null).orElseThrow(ModerationContentService::missing);
    }
    private static ApiFailure missing() { return new ApiFailure(404, "CONTENT_NOT_FOUND", "This content could not be found."); }
}
