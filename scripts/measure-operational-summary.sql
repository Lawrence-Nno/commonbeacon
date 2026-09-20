-- Rollback-only temporary fixtures. Run with psql -v ON_ERROR_STOP=1.
-- Mirrors the summary statement and relevant existing indexes; not a production benchmark.
BEGIN;
CREATE TEMP TABLE question (id uuid PRIMARY KEY, accepted_reply_id uuid, visibility text) ON COMMIT DROP;
CREATE TEMP TABLE reply (id uuid PRIMARY KEY, question_id uuid, visibility text) ON COMMIT DROP;
CREATE TEMP TABLE content_report (id uuid PRIMARY KEY, status text, created_at timestamptz) ON COMMIT DROP;
CREATE TEMP TABLE knowledge_article (id uuid PRIMARY KEY, status text, published_at timestamptz, updated_at timestamptz) ON COMMIT DROP;
INSERT INTO question SELECT md5('q'||n)::uuid, CASE WHEN n%2=0 THEN md5('r'||n)::uuid END,
    CASE WHEN n%5=0 THEN 'HIDDEN' ELSE 'VISIBLE' END FROM generate_series(1,10000) n;
INSERT INTO reply SELECT md5('r'||n)::uuid, md5('q'||n)::uuid,
    CASE WHEN n%4=0 THEN 'HIDDEN' ELSE 'VISIBLE' END FROM generate_series(1,10000) n;
INSERT INTO content_report SELECT md5('report'||n)::uuid,
    CASE WHEN n%3=0 THEN 'OPEN' ELSE 'RESOLVED' END, now() FROM generate_series(1,6000) n;
INSERT INTO knowledge_article SELECT md5('article'||n)::uuid,
    CASE WHEN n%3=0 THEN 'PUBLISHED' WHEN n%3=1 THEN 'DRAFT' ELSE 'ARCHIVED' END, now(), now()
    FROM generate_series(1,3000) n;
CREATE INDEX ON reply(question_id);
CREATE INDEX ON content_report(status,created_at DESC,id DESC);
CREATE INDEX ON knowledge_article(status,updated_at DESC,id DESC);
CREATE INDEX ON knowledge_article(published_at DESC,id DESC) WHERE status='PUBLISHED';
ANALYZE question;
ANALYZE reply;
ANALYZE content_report;
ANALYZE knowledge_article;
PREPARE operational_summary AS
SELECT
    (SELECT count(*) FROM question q WHERE q.visibility='VISIBLE'
        AND NOT EXISTS (SELECT 1 FROM reply r WHERE r.id=q.accepted_reply_id
            AND r.question_id=q.id AND r.visibility='VISIBLE')) AS unanswered,
    (SELECT count(*) FROM content_report WHERE status='OPEN') AS reports,
    (SELECT count(*) FROM knowledge_article WHERE status='PUBLISHED') AS articles;
EXECUTE operational_summary;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE operational_summary;
DEALLOCATE operational_summary;
ROLLBACK;
