package com.lawrencenno.commonbeacon.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ArticleReadRepository {
    private final JdbcTemplate jdbc;
    public ArticleReadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    // Summary queries deliberately exclude body; no unbounded lazy author loading.
    private static final String SUMMARY = """
            SELECT a.id, a.slug, a.title, a.status, a.author_id, u.display_name,
                   a.created_at, a.updated_at, a.published_at, a.version
            FROM knowledge_article a JOIN app_user u ON u.id=a.author_id
            """;
    public long count(String status) {
        return status == null ? jdbc.queryForObject("SELECT count(*) FROM knowledge_article", Long.class)
                : jdbc.queryForObject("SELECT count(*) FROM knowledge_article WHERE status=?", Long.class, status);
    }
    public List<ArticleViews.Summary> published(int size, long offset) {
        return jdbc.query(SUMMARY + " WHERE a.status='PUBLISHED' ORDER BY a.published_at DESC,a.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new ArticleViews.Summary(uuid(rs, "id"), rs.getString("slug"), rs.getString("title"), author(rs),
                        instant(rs, "published_at"), instant(rs, "updated_at")), size, offset);
    }
    public List<ArticleViews.AdminSummary> admin(String status, int size, long offset) {
        String filter = status == null ? "" : " WHERE a.status=?";
        Object[] arguments = status == null ? new Object[]{size, offset} : new Object[]{status, size, offset};
        return jdbc.query(SUMMARY + filter + " ORDER BY a.updated_at DESC,a.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new ArticleViews.AdminSummary(uuid(rs, "id"), rs.getString("slug"), rs.getString("title"),
                        KnowledgeArticle.Status.valueOf(rs.getString("status")), author(rs), instant(rs, "created_at"),
                        instant(rs, "updated_at"), instant(rs, "published_at"), rs.getLong("version")), arguments);
    }
    private static UUID uuid(ResultSet rs, String field) throws SQLException { return rs.getObject(field, UUID.class); }
    private static ArticleViews.Author author(ResultSet rs) throws SQLException { return new ArticleViews.Author(uuid(rs, "author_id"), rs.getString("display_name")); }
    private static Instant instant(ResultSet rs, String field) throws SQLException { var time = rs.getTimestamp(field); return time == null ? null : time.toInstant(); }
}
