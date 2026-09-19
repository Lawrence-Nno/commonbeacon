package com.lawrencenno.commonbeacon.moderation;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only application interface; database administrators can still change storage. */
@Repository
public class ModerationActionRepository {
    private final JdbcTemplate jdbc;
    public ModerationActionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void appendHide(UUID actorId, UUID questionId, UUID replyId, String reason) {
        jdbc.update("INSERT INTO moderation_action(id, actor_id, question_id, reply_id, action, reason) VALUES (?, ?, ?, ?, 'HIDE', ?)",
                UUID.randomUUID(), actorId, questionId, replyId, reason);
    }
}
