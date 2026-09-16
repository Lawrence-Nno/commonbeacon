package com.lawrencenno.commonbeacon.reply;

import com.lawrencenno.commonbeacon.board.BoardRepository;
import com.lawrencenno.commonbeacon.identity.IdentityService;
import com.lawrencenno.commonbeacon.identity.UserRepository;
import com.lawrencenno.commonbeacon.question.Question;
import com.lawrencenno.commonbeacon.question.QuestionRepository;
import com.lawrencenno.commonbeacon.shared.*;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReplyService {
    private final ReplyRepository replies;
    private final QuestionRepository questions;
    private final BoardRepository boards;
    private final UserRepository users;
    private final IdentityService identity;

    public ReplyService(ReplyRepository replies, QuestionRepository questions, BoardRepository boards,
                        UserRepository users, IdentityService identity) {
        this.replies = replies;
        this.questions = questions;
        this.boards = boards;
        this.users = users;
        this.identity = identity;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReplySummary> list(UUID questionId, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiFailure(400, "INVALID_PAGE", "Use a nonnegative page and a size from 1 to 100.");
        }
        questions.findVisibleBoardId(questionId).orElseThrow(ReplyService::missingQuestion);
        var ordering = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
        return PageResponse.from(replies.findByQuestionIdAndVisibilityAndQuestionVisibility(
                questionId, ContentVisibility.VISIBLE, ContentVisibility.VISIBLE,
                PageRequest.of(page, size, ordering)).map(ReplySummary::from));
    }

    @Transactional(readOnly = true)
    public ReplySummary get(UUID id) { return ReplySummary.from(find(id)); }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ReplySummary create(UUID questionId, CreateReplyRequest request, Authentication authentication) {
        var question = writableQuestion(questionId);
        var author = users.getReferenceById(identity.current(authentication).id());
        return ReplySummary.from(replies.saveAndFlush(Reply.create(question, author, request.body())));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ReplySummary update(UUID id, UpdateReplyRequest request, Authentication authentication) {
        var questionId = replies.findVisibleQuestionId(id).orElseThrow(ReplyService::missingReply);
        writableQuestion(questionId);
        var reply = find(id);
        identity.requireOwner(authentication, reply.getAuthor().getId());
        if (reply.getVersion() != request.expectedVersion()) {
            throw new ApiFailure(409, "STALE_EDIT", "This reply changed. Reload it before saving again.");
        }
        reply.edit(request.body());
        return ReplySummary.from(replies.saveAndFlush(reply));
    }

    private Question writableQuestion(UUID id) {
        // Lock order: board, then question, then reply UPDATE. Scalar lookup avoids stale managed state.
        var boardId = questions.findVisibleBoardId(id).orElseThrow(ReplyService::missingQuestion);
        var board = boards.findForUpdate(boardId).orElseThrow(ReplyService::missingQuestion);
        var question = questions.findVisibleForUpdate(id).orElseThrow(ReplyService::missingQuestion);
        if (board.isArchived()) throw new ApiFailure(409, "BOARD_ARCHIVED", "This board is archived and is closed to changes.");
        return question;
    }

    private Reply find(UUID id) {
        return replies.findByIdAndVisibilityAndQuestionVisibility(id, ContentVisibility.VISIBLE, ContentVisibility.VISIBLE)
                .orElseThrow(ReplyService::missingReply);
    }
    private static ApiFailure missingReply() { return new ApiFailure(404, "REPLY_NOT_FOUND", "This reply could not be found."); }
    private static ApiFailure missingQuestion() { return new ApiFailure(404, "QUESTION_NOT_FOUND", "This question could not be found."); }
}
