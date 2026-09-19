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
    public void appendRestore(UUID actorId, UUID questionId, UUID replyId, String reason) {
        jdbc.update("INSERT INTO moderation_action(id, actor_id, question_id, reply_id, action, reason) VALUES (?, ?, ?, ?, 'RESTORE', ?)",
                UUID.randomUUID(), actorId, questionId, replyId, reason);
    }
    public long count(UUID targetId, boolean reply) {
        return jdbc.queryForObject("SELECT count(*) FROM moderation_action WHERE " + targetColumn(reply) + "=?", Long.class, targetId);
    }
    public java.util.List<ModerationAction> list(UUID targetId, boolean reply, int size, long offset) {
        return jdbc.query("SELECT a.*, u.display_name FROM moderation_action a JOIN app_user u ON u.id=a.actor_id WHERE a."
                + targetColumn(reply) + "=? ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?", (rs, row) ->
                new ModerationAction(rs.getObject("id", UUID.class),
                        new ModerationReport.Actor(rs.getObject("actor_id", UUID.class), rs.getString("display_name")),
                        reply ? "REPLY" : "QUESTION", targetId, rs.getString("action"), rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()), targetId, size, offset);
    }
    private static String targetColumn(boolean reply) { return reply ? "reply_id" : "question_id"; }
}
