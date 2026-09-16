package com.lawrencenno.commonbeacon.question;

import java.time.Instant;
import java.util.UUID;

public record QuestionDetail(UUID id, BoardInfo board, String title, String body,
                             QuestionAuthor author, Instant createdAt, Instant updatedAt, long version) {
    public record BoardInfo(UUID id, String name, boolean archived) {}

    static QuestionDetail from(Question question) {
        var board = question.getBoard();
        return new QuestionDetail(question.getId(), new BoardInfo(board.getId(), board.getName(), board.isArchived()),
                question.getTitle(), question.getBody(), QuestionAuthor.from(question.getAuthor()),
                question.getCreatedAt(), question.getUpdatedAt(), question.getVersion());
    }
}
