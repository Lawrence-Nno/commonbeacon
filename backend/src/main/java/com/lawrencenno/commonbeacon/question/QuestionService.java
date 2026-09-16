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

    public QuestionService(QuestionRepository questions, BoardRepository boards,
                           UserRepository users, IdentityService identity) {
        this.questions = questions;
        this.boards = boards;
        this.users = users;
        this.identity = identity;
    }

    @Transactional(readOnly = true)
    public PageResponse<QuestionSummary> list(UUID boardId, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiFailure(400, "INVALID_PAGE", "Use a nonnegative page and a size from 1 to 100; the page offset must fit a 32-bit integer.");
        }
        if (!boards.existsById(boardId)) throw boardMissing();
        var ordering = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return PageResponse.from(questions.findByBoardIdAndVisibility(boardId, ContentVisibility.VISIBLE,
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
        var question = questions.findByIdAndVisibility(id, ContentVisibility.VISIBLE)
                .orElseThrow(QuestionService::questionMissing);
        identity.requireOwner(authentication, question.getAuthor().getId());
        var board = boards.findForUpdate(question.getBoard().getId()).orElseThrow(QuestionService::boardMissing);
        requireOpen(board);
        if (question.getVersion() != request.expectedVersion()) {
            throw new ApiFailure(409, "STALE_EDIT", "This question changed. Reload it before saving again.");
        }
        question.edit(request);
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
