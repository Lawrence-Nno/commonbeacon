-- Stored generated columns backfill existing rows and maintain vectors on every write.
-- Explicit configuration makes results independent of default_text_search_config.
ALTER TABLE question ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english'::regconfig, title), 'A') ||
    setweight(to_tsvector('english'::regconfig, body), 'B')
) STORED;
ALTER TABLE knowledge_article ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english'::regconfig, title), 'A') ||
    setweight(to_tsvector('english'::regconfig, body), 'B')
) STORED;
CREATE INDEX ix_question_public_search ON question USING GIN(search_vector) WHERE visibility='VISIBLE';
CREATE INDEX ix_article_public_search ON knowledge_article USING GIN(search_vector) WHERE status='PUBLISHED';
