package com.lawrencenno.commonbeacon.board;

import java.time.Instant;
import java.util.UUID;

public record BoardSummary(UUID id, String slug, String name, String description,
                           boolean archived, Instant createdAt, long version) {
    static BoardSummary from(Board board) {
        return new BoardSummary(board.getId(), board.getSlug(), board.getName(),
                board.getDescription(), board.isArchived(), board.getCreatedAt(), board.getVersion());
    }
}
