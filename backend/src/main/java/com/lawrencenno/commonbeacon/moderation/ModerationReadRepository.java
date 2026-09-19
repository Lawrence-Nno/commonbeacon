package com.lawrencenno.commonbeacon.moderation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.lawrencenno.commonbeacon.moderation.ModerationReport.Actor;
import com.lawrencenno.commonbeacon.moderation.ModerationContext.*;
import com.lawrencenno.commonbeacon.shared.ContentVisibility;

/** Privileged projections only. Never reuse these queries for public content reads. */
@Repository
public class ModerationReadRepository {
    private final JdbcTemplate jdbc;
    public ModerationReadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String REPORT_SELECT = """
        SELECT cr.*, reporter.display_name AS reporter_name, resolver.display_name AS resolver_name,
               COALESCE(cr.question_id, reply.question_id) AS parent_question_id
        FROM content_report cr
        JOIN app_user reporter ON reporter.id = cr.reporter_id
        LEFT JOIN app_user resolver ON resolver.id = cr.resolver_id
        LEFT JOIN reply ON reply.id = cr.reply_id
        """;
    private static final RowMapper<ModerationReport> REPORT = (rs, row) -> {
        UUID replyId = uuid(rs, "reply_id");
        UUID resolverId = uuid(rs, "resolver_id");
        return new ModerationReport(uuid(rs, "id"), rs.getString("status"), rs.getString("reason"),
                new Actor(uuid(rs, "reporter_id"), rs.getString("reporter_name")),
                replyId == null ? "QUESTION" : "REPLY", replyId == null ? uuid(rs, "question_id") : replyId,
                uuid(rs, "parent_question_id"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getLong("version"), instant(rs, "resolved_at"),
                resolverId == null ? null : new Actor(resolverId, rs.getString("resolver_name")),
                rs.getString("resolution_decision"), rs.getString("resolution_note"));
    };

    public long count(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM content_report WHERE status=?", Long.class, status);
    }
    public List<ModerationReport> list(String status, int size, long offset) {
        return jdbc.query(REPORT_SELECT + " WHERE cr.status=? ORDER BY cr.created_at ASC, cr.id ASC LIMIT ? OFFSET ?",
                REPORT, status, size, offset);
    }
    public Optional<ModerationReport> find(UUID id) {
        return jdbc.query(REPORT_SELECT + " WHERE cr.id=?", REPORT, id).stream().findFirst();
    }
    public Optional<ModerationContext> context(ModerationReport report) {
        UUID replyId = "REPLY".equals(report.targetKind()) ? report.targetId() : null;
        return context(report.questionId(), replyId);
    }
    public Optional<ModerationContext> context(UUID questionId, UUID replyId) {
        return jdbc.query("""
            SELECT b.id AS board_id, b.name AS board_name, b.archived,
                   q.id AS question_id, q.title, q.body AS question_body, q.author_id AS question_author_id,
                   qa.display_name AS question_author_name, q.visibility AS question_visibility,
                   q.version AS question_version, q.accepted_reply_id,
                   r.id AS reply_id, r.body AS reply_body, r.author_id AS reply_author_id,
                   ra.display_name AS reply_author_name, r.visibility AS reply_visibility, r.version AS reply_version
            FROM question q JOIN board b ON b.id=q.board_id JOIN app_user qa ON qa.id=q.author_id
            LEFT JOIN reply r ON r.id=? AND r.question_id=q.id
            LEFT JOIN app_user ra ON ra.id=r.author_id
            WHERE q.id=?
            """, (rs, row) -> {
                var qVisibility = ContentVisibility.valueOf(rs.getString("question_visibility"));
                var question = new QuestionContext(uuid(rs, "question_id"), rs.getString("title"),
                        rs.getString("question_body"), new Actor(uuid(rs, "question_author_id"), rs.getString("question_author_name")),
                        qVisibility, rs.getLong("question_version"), uuid(rs, "accepted_reply_id"));
                var reply = uuid(rs, "reply_id") == null ? null : new ReplyContext(uuid(rs, "reply_id"),
                        rs.getString("reply_body"), new Actor(uuid(rs, "reply_author_id"), rs.getString("reply_author_name")),
                        ContentVisibility.valueOf(rs.getString("reply_visibility")), rs.getLong("reply_version"));
                return new ModerationContext(new BoardContext(uuid(rs, "board_id"), rs.getString("board_name"), rs.getBoolean("archived")),
                        question, reply, replyId == null ? "QUESTION" : "REPLY", replyId == null ? questionId : replyId, qVisibility == ContentVisibility.VISIBLE
                        && (replyId == null || (reply != null && reply.visibility() == ContentVisibility.VISIBLE)));
            }, replyId, questionId).stream().filter(context -> replyId == null || context.reply() != null).findFirst();
    }
    private static UUID uuid(ResultSet rs, String name) throws SQLException { return rs.getObject(name, UUID.class); }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var timestamp = rs.getTimestamp(name);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
