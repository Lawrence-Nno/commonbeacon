package com.lawrencenno.commonbeacon.reply;

import java.time.Instant;
import java.util.UUID;

public record ReplySummary(UUID id, UUID questionId, String body, Author author,
                           Instant createdAt, Instant updatedAt, long version) {
    public record Author(UUID id, String displayName) {}

    static ReplySummary from(Reply reply) {
        return new ReplySummary(reply.getId(), reply.getQuestion().getId(), reply.getBody(),
                new Author(reply.getAuthor().getId(), reply.getAuthor().getDisplayName()),
                reply.getCreatedAt(), reply.getUpdatedAt(), reply.getVersion());
    }
}
