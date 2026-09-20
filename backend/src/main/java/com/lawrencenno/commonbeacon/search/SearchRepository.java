package com.lawrencenno.commonbeacon.search;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SearchRepository {
    private final JdbcTemplate jdbc;
    public SearchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // One shared visibility/matching expression for both count and globally paged hits.
    private static final String MATCHES = """
            SELECT 'ARTICLE' AS kind, id, title, left(body, 241) AS snippet,
                   '/knowledge/' || slug AS url, 0::double precision AS rank
            FROM knowledge_article WHERE status='PUBLISHED' AND title ILIKE ? ESCAPE '!'
            UNION ALL
            SELECT 'QUESTION' AS kind, id, title, left(body, 241) AS snippet,
                   '/questions/' || id::text AS url, 0::double precision AS rank
            FROM question WHERE visibility='VISIBLE' AND title ILIKE ? ESCAPE '!'
            """;

    static String pattern(String query) {
        return "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    public long count(String query) {
        String pattern = pattern(query);
        return jdbc.queryForObject("SELECT count(*) FROM (" + MATCHES + ") hits", Long.class, pattern, pattern);
    }
    public List<SearchHit> hits(String query, int size, long offset) {
        String pattern = pattern(query);
        return jdbc.query("SELECT * FROM (" + MATCHES + """
                ) hits ORDER BY rank DESC, CASE kind WHEN 'ARTICLE' THEN 0 ELSE 1 END, id ASC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new SearchHit(SearchHit.Kind.valueOf(rs.getString("kind")),
                        rs.getObject("id", UUID.class), rs.getString("title"), SearchHit.snippet(rs.getString("snippet")),
                        rs.getString("url"), rs.getDouble("rank")), pattern, pattern, size, offset);
    }
}
