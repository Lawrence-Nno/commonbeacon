package com.lawrencenno.commonbeacon.question;

import java.time.Instant;
import java.util.UUID;

public record QuestionSummary(UUID id, UUID boardId, String title, QuestionAuthor author,
                              Instant createdAt, Instant updatedAt, long version) {
    static QuestionSummary from(Question question) {
        return new QuestionSummary(question.getId(), question.getBoard().getId(), question.getTitle(),
                QuestionAuthor.from(question.getAuthor()), question.getCreatedAt(),
                question.getUpdatedAt(), question.getVersion());
    }
}
