package com.lawrencenno.commonbeacon.moderation;

import java.util.UUID;
import com.lawrencenno.commonbeacon.moderation.ModerationReport.Actor;
import com.lawrencenno.commonbeacon.shared.ContentVisibility;

public record ModerationContext(BoardContext board, QuestionContext question, ReplyContext reply,
        String targetKind, UUID targetId, boolean effectivePublicVisibility) {
    public record BoardContext(UUID id, String name, boolean archived) {}
    public record QuestionContext(UUID id, String title, String body, Actor author,
            ContentVisibility visibility, long version, UUID acceptedReplyId) {}
    public record ReplyContext(UUID id, String body, Actor author, ContentVisibility visibility, long version) {}
}
