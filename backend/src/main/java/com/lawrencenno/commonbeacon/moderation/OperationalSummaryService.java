package com.lawrencenno.commonbeacon.moderation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMINISTRATOR')")
@Transactional(readOnly = true)
public class OperationalSummaryService {
    private final JdbcTemplate jdbc;
    public OperationalSummaryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // A single statement gives all counts one MVCC snapshot, without loading entities.
    public OperationalSummary get() {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT count(*) FROM question q WHERE q.visibility='VISIBLE'
                        AND NOT EXISTS (SELECT 1 FROM reply r WHERE r.id=q.accepted_reply_id
                            AND r.question_id=q.id AND r.visibility='VISIBLE')) AS unanswered,
                    (SELECT count(*) FROM content_report WHERE status='OPEN') AS reports,
                    (SELECT count(*) FROM knowledge_article WHERE status='PUBLISHED') AS articles
                """, (rs, row) -> new OperationalSummary(rs.getLong("unanswered"), rs.getLong("reports"), rs.getLong("articles")));
    }
}
