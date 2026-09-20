-- Run with psql -v ON_ERROR_STOP=1. Temporary fixtures never change app rows.
-- Mirrors the Stage 9 repository query shape; this is not a production benchmark.
BEGIN;
CREATE TEMP TABLE knowledge_article (
    id uuid, slug text, title text, body text, status text,
    search_vector tsvector GENERATED ALWAYS AS (setweight(to_tsvector('english'::regconfig,title),'A') || setweight(to_tsvector('english'::regconfig,body),'B')) STORED
) ON COMMIT DROP;
CREATE TEMP TABLE question (
    id uuid, title text, body text, visibility text,
    search_vector tsvector GENERATED ALWAYS AS (setweight(to_tsvector('english'::regconfig,title),'A') || setweight(to_tsvector('english'::regconfig,body),'B')) STORED
) ON COMMIT DROP;
INSERT INTO knowledge_article(id,slug,title,body,status)
SELECT md5('article-' || n)::uuid, 'guide-' || n,
       CASE WHEN n=42 THEN 'selectivebeacon' ELSE 'common guide ' || n END,
       repeat('Fictional plain text. ', 50), CASE WHEN n%4=0 THEN 'DRAFT' ELSE 'PUBLISHED' END
FROM generate_series(1,20000) n;
INSERT INTO question(id,title,body,visibility)
SELECT md5('question-' || n)::uuid,
       CASE WHEN n=42 THEN 'selectivebeacon' ELSE 'common question ' || n END,
       repeat('Fictional plain text. ', 50), CASE WHEN n%5=0 THEN 'HIDDEN' ELSE 'VISIBLE' END
FROM generate_series(1,20000) n;
CREATE INDEX ON knowledge_article USING GIN(search_vector) WHERE status='PUBLISHED';
CREATE INDEX ON question USING GIN(search_vector) WHERE visibility='VISIBLE';
ANALYZE knowledge_article;
ANALYZE question;
PREPARE full_text_search_count(text,text) AS
SELECT count(*) FROM (
    SELECT 'ARTICLE' AS kind, id, title, left(body,241) AS snippet,
           '/knowledge/' || slug AS url, ts_rank_cd(search_vector,websearch_to_tsquery('english',$1)) AS rank
    FROM knowledge_article WHERE status='PUBLISHED' AND numnode(websearch_to_tsquery('english',$1))>0 AND search_vector @@ websearch_to_tsquery('english',$1)
    UNION ALL
    SELECT 'QUESTION', id, title, left(body,241), '/questions/' || id::text, ts_rank_cd(search_vector,websearch_to_tsquery('english',$2))
    FROM question WHERE visibility='VISIBLE' AND numnode(websearch_to_tsquery('english',$2))>0 AND search_vector @@ websearch_to_tsquery('english',$2)
) hits;
PREPARE full_text_search_hits(text,text,integer,bigint) AS
SELECT * FROM (
    SELECT 'ARTICLE' AS kind, id, title, left(body,241) AS snippet,
           '/knowledge/' || slug AS url, ts_rank_cd(search_vector,websearch_to_tsquery('english',$1)) AS rank
    FROM knowledge_article WHERE status='PUBLISHED' AND numnode(websearch_to_tsquery('english',$1))>0 AND search_vector @@ websearch_to_tsquery('english',$1)
    UNION ALL
    SELECT 'QUESTION', id, title, left(body,241), '/questions/' || id::text, ts_rank_cd(search_vector,websearch_to_tsquery('english',$2))
    FROM question WHERE visibility='VISIBLE' AND numnode(websearch_to_tsquery('english',$2))>0 AND search_vector @@ websearch_to_tsquery('english',$2)
) hits ORDER BY rank DESC, CASE kind WHEN 'ARTICLE' THEN 0 ELSE 1 END, id ASC LIMIT $3 OFFSET $4;
\echo Selective: 2 eligible matches in 40000 fixture records
EXECUTE full_text_search_count('selectivebeacon', 'selectivebeacon');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE full_text_search_count('selectivebeacon', 'selectivebeacon');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE full_text_search_hits('selectivebeacon', 'selectivebeacon',20,0);
\echo Broad: 30998 eligible common-title matches in 40000 fixture records
EXECUTE full_text_search_count('common', 'common');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE full_text_search_count('common', 'common');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE full_text_search_hits('common', 'common',20,0);
DEALLOCATE full_text_search_count;
DEALLOCATE full_text_search_hits;
ROLLBACK;
