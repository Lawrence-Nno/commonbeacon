package com.lawrencenno.commonbeacon.moderation;

import java.time.Instant;
import java.util.UUID;

public record ModerationAction(UUID id, ModerationReport.Actor actor, String targetKind,
        UUID targetId, String action, String reason, Instant createdAt) {}
