package com.lawrencenno.commonbeacon.board;

import com.lawrencenno.commonbeacon.shared.ApiFailure;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardService {
    private final BoardRepository boards;

    public BoardService(BoardRepository boards) { this.boards = boards; }

    @Transactional(readOnly = true)
    public List<BoardSummary> list() {
        return boards.findAllByOrderByNameAscIdAsc().stream().map(BoardSummary::from).toList();
    }

    @Transactional(readOnly = true)
    public BoardSummary get(UUID id) { return BoardSummary.from(find(id)); }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public BoardSummary create(CreateBoardRequest request) {
        return save(Board.create(request));
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Transactional
    public BoardSummary update(UUID id, UpdateBoardRequest request) {
        var board = find(id);
        if (board.getVersion() != request.expectedVersion()) {
            throw new ApiFailure(409, "STALE_EDIT", "This board changed. Reload it before saving again.");
        }
        if (request.slug() == null && request.name() == null
                && request.description() == null && request.archived() == null) {
            throw new ApiFailure(400, "INVALID_REQUEST", "Provide at least one board field to update.");
        }
        board.update(request);
        return save(board);
    }

    private Board find(UUID id) {
        return boards.findById(id).orElseThrow(() ->
                new ApiFailure(404, "BOARD_NOT_FOUND", "This board could not be found."));
    }

    private BoardSummary save(Board board) {
        try {
            return BoardSummary.from(boards.saveAndFlush(board));
        } catch (DataIntegrityViolationException exception) {
            throw new ApiFailure(409, "BOARD_CONFLICT", "That board slug is already in use.");
        }
    }
}
