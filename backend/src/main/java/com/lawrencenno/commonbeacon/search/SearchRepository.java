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
                   '/knowledge/' || slug AS url, ts_rank_cd(search_vector, parsed.query) AS rank
            FROM knowledge_article CROSS JOIN websearch_to_tsquery('english', ?) AS parsed(query)
            WHERE status='PUBLISHED' AND numnode(parsed.query)>0 AND search_vector @@ parsed.query
            UNION ALL
            SELECT 'QUESTION' AS kind, id, title, left(body, 241) AS snippet,
                   '/questions/' || id::text AS url, ts_rank_cd(search_vector, parsed.query) AS rank
            FROM question CROSS JOIN websearch_to_tsquery('english', ?) AS parsed(query)
            WHERE visibility='VISIBLE' AND numnode(parsed.query)>0 AND search_vector @@ parsed.query
            """;

    public long count(String query) {
        return jdbc.queryForObject("SELECT count(*) FROM (" + MATCHES + ") hits", Long.class, query, query);
    }
    public List<SearchHit> hits(String query, int size, long offset) {
        return jdbc.query("SELECT * FROM (" + MATCHES + """
                ) hits ORDER BY rank DESC, CASE kind WHEN 'ARTICLE' THEN 0 ELSE 1 END, id ASC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new SearchHit(SearchHit.Kind.valueOf(rs.getString("kind")),
                        rs.getObject("id", UUID.class), rs.getString("title"), SearchHit.snippet(rs.getString("snippet")),
                        rs.getString("url"), rs.getDouble("rank")), query, query, size, offset);
    }
}
