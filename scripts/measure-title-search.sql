-- Run with psql -v ON_ERROR_STOP=1. Temporary fixtures never change app rows.
-- Mirrors the Stage 8 repository query shape; this is not a production benchmark.
BEGIN;
CREATE TEMP TABLE knowledge_article (
    id uuid, slug text, title text, body text, status text
) ON COMMIT DROP;
CREATE TEMP TABLE question (
    id uuid, title text, body text, visibility text
) ON COMMIT DROP;
INSERT INTO knowledge_article
SELECT md5('article-' || n)::uuid, 'guide-' || n,
       CASE WHEN n=42 THEN 'needle-stage8' ELSE 'common guide ' || n END,
       repeat('Fictional plain text. ', 50), CASE WHEN n%4=0 THEN 'DRAFT' ELSE 'PUBLISHED' END
FROM generate_series(1,2000) n;
INSERT INTO question
SELECT md5('question-' || n)::uuid,
       CASE WHEN n=42 THEN 'needle-stage8' ELSE 'common question ' || n END,
       repeat('Fictional plain text. ', 50), CASE WHEN n%5=0 THEN 'HIDDEN' ELSE 'VISIBLE' END
FROM generate_series(1,2000) n;
ANALYZE knowledge_article;
ANALYZE question;
PREPARE title_search_count(text,text) AS
SELECT count(*) FROM (
    SELECT 'ARTICLE' AS kind, id, title, left(body,241) AS snippet,
           '/knowledge/' || slug AS url, 0::double precision AS rank
    FROM knowledge_article WHERE status='PUBLISHED' AND title ILIKE $1 ESCAPE '!'
    UNION ALL
    SELECT 'QUESTION', id, title, left(body,241), '/questions/' || id::text, 0::double precision
    FROM question WHERE visibility='VISIBLE' AND title ILIKE $2 ESCAPE '!'
) hits;
PREPARE title_search_hits(text,text,integer,bigint) AS
SELECT * FROM (
    SELECT 'ARTICLE' AS kind, id, title, left(body,241) AS snippet,
           '/knowledge/' || slug AS url, 0::double precision AS rank
    FROM knowledge_article WHERE status='PUBLISHED' AND title ILIKE $1 ESCAPE '!'
    UNION ALL
    SELECT 'QUESTION', id, title, left(body,241), '/questions/' || id::text, 0::double precision
    FROM question WHERE visibility='VISIBLE' AND title ILIKE $2 ESCAPE '!'
) hits ORDER BY rank DESC, CASE kind WHEN 'ARTICLE' THEN 0 ELSE 1 END, id ASC LIMIT $3 OFFSET $4;
\echo Selective: 2 eligible matches in 4000 fixture records
EXECUTE title_search_count('%needle-stage8%', '%needle-stage8%');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE title_search_count('%needle-stage8%', '%needle-stage8%');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE title_search_hits('%needle-stage8%', '%needle-stage8%',20,0);
\echo Broad: 3098 eligible common-title matches in 4000 fixture records
EXECUTE title_search_count('%common%', '%common%');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE title_search_count('%common%', '%common%');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE title_search_hits('%common%', '%common%',20,0);
DEALLOCATE title_search_count;
DEALLOCATE title_search_hits;
ROLLBACK;
