package com.lawrencenno.commonbeacon.board;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/boards")
public class BoardController {
    private final BoardService boards;

    public BoardController(BoardService boards) { this.boards = boards; }

    @GetMapping
    public List<BoardSummary> list() { return boards.list(); }

    @GetMapping("/{id}")
    public BoardSummary get(@PathVariable UUID id) { return boards.get(id); }

    @PostMapping
    public ResponseEntity<BoardSummary> create(@Valid @RequestBody CreateBoardRequest request) {
        var board = boards.create(request);
        return ResponseEntity.created(URI.create("/api/v1/boards/" + board.id())).body(board);
    }

    @PatchMapping("/{id}")
    public BoardSummary update(@PathVariable UUID id, @Valid @RequestBody UpdateBoardRequest request) {
        return boards.update(id, request);
    }
}
