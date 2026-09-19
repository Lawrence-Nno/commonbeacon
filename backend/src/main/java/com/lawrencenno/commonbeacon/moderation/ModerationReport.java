package com.lawrencenno.commonbeacon.moderation;

import java.time.Instant;
import java.util.UUID;

public record ModerationReport(UUID id, String status, String reason, Actor reporter,
        String targetKind, UUID targetId, UUID questionId, Instant createdAt, Instant updatedAt,
        long version, Instant resolvedAt, Actor resolver, String resolutionDecision, String resolutionNote) {
    public record Actor(UUID id, String displayName) {}
}
