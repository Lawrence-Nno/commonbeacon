package com.lawrencenno.commonbeacon.question;

import com.lawrencenno.commonbeacon.board.Board;
import com.lawrencenno.commonbeacon.board.BoardRepository;
import com.lawrencenno.commonbeacon.identity.IdentityService;
import com.lawrencenno.commonbeacon.identity.UserRepository;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.shared.ContentVisibility;
import com.lawrencenno.commonbeacon.shared.PageResponse;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionService {
    private final QuestionRepository questions;
    private final BoardRepository boards;
    private final UserRepository users;
    private final IdentityService identity;
    private final com.lawrencenno.commonbeacon.reply.ReplyRepository replies;

    public QuestionService(QuestionRepository questions, BoardRepository boards,
                           UserRepository users, IdentityService identity, com.lawrencenno.commonbeacon.reply.ReplyRepository replies) {
        this.questions = questions;
        this.boards = boards;
        this.users = users;
        this.identity = identity;
        this.replies = replies;
    }

    @Transactional(readOnly = true)
    public PageResponse<QuestionSummary> list(UUID boardId, int page, int size) {
        return list(boardId, page, size, "all");
    }

    @Transactional(readOnly = true)
    public PageResponse<QuestionSummary> list(UUID boardId, int page, int size, String status) {
        if (!java.util.Set.of("all", "solved", "unanswered").contains(status)) {
            throw new ApiFailure(400, "INVALID_STATUS", "Use all, solved, or unanswered.");
        }
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiFailure(400, "INVALID_PAGE", "Use a nonnegative page and a size from 1 to 100; the page offset must fit a 32-bit integer.");
        }
        if (!boards.existsById(boardId)) throw boardMissing();
        var ordering = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return PageResponse.from(questions.findFiltered(boardId, status,
                PageRequest.of(page, size, ordering)).map(QuestionSummary::from));
    }

    @Transactional(readOnly = true)
    public QuestionDetail get(UUID id) {
        return QuestionDetail.from(questions.findDetailedByIdAndVisibility(id, ContentVisibility.VISIBLE)
                .orElseThrow(QuestionService::questionMissing));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public QuestionDetail create(UUID boardId, CreateQuestionRequest request, Authentication authentication) {
        // The board lock serializes activity with archival; it is held until commit.
        var board = boards.findForUpdate(boardId).orElseThrow(QuestionService::boardMissing);
        requireOpen(board);
        var author = users.getReferenceById(identity.current(authentication).id());
        return QuestionDetail.from(questions.saveAndFlush(Question.create(board, author, request)));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public QuestionDetail update(UUID id, UpdateQuestionRequest request, Authentication authentication) {
        var boardId = questions.findVisibleBoardId(id).orElseThrow(QuestionService::questionMissing);
        var board = boards.findForUpdate(boardId).orElseThrow(QuestionService::boardMissing);
        var question = questions.findVisibleForUpdate(id).orElseThrow(QuestionService::questionMissing);
        identity.requireOwner(authentication, question.getAuthor().getId());
        requireOpen(board);
        if (question.getVersion() != request.expectedVersion()) {
            throw new ApiFailure(409, "STALE_EDIT", "This question changed. Reload it before saving again.");
        }
        question.edit(request);
        return QuestionDetail.from(questions.saveAndFlush(question));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public QuestionDetail accept(UUID id, UUID replyId, long expectedVersion, Authentication authentication) {
        if (expectedVersion < 0) throw new ApiFailure(400, "INVALID_VERSION", "Use a nonnegative expectedVersion.");
        // All thread writes lock board -> question -> reply. Do not load managed rows before their locks.
        var boardId = questions.findVisibleBoardId(id).orElseThrow(QuestionService::questionMissing);
        var board = boards.findForUpdate(boardId).orElseThrow(QuestionService::boardMissing);
        var question = questions.findVisibleForUpdate(id).orElseThrow(QuestionService::questionMissing);
        identity.requireOwner(authentication, question.getAuthor().getId());
        requireOpen(board);
        if (question.getVersion() != expectedVersion) {
            throw new ApiFailure(409, "STALE_EDIT", "This question changed. Reload it before changing the solution.");
        }
        var reply = replyId == null ? null : replies.findVisibleForUpdate(replyId, id)
                .orElseThrow(() -> new ApiFailure(404, "REPLY_NOT_FOUND", "A visible reply on this question is required."));
        question.accept(reply);
        return QuestionDetail.from(questions.saveAndFlush(question));
    }

    private static void requireOpen(Board board) {
        if (board.isArchived()) throw new ApiFailure(409, "BOARD_ARCHIVED", "This board is archived and is closed to changes.");
    }
    private static ApiFailure boardMissing() {
        return new ApiFailure(404, "BOARD_NOT_FOUND", "This board could not be found.");
    }
    private static ApiFailure questionMissing() {
        return new ApiFailure(404, "QUESTION_NOT_FOUND", "This question could not be found.");
    }
}
