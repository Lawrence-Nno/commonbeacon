CREATE TABLE knowledge_article (
    id UUID PRIMARY KEY,
    slug VARCHAR(100) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    author_id UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ux_article_slug UNIQUE (slug),
    CONSTRAINT ck_article_slug CHECK (char_length(slug) BETWEEN 3 AND 100 AND slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_article_title CHECK (title = btrim(title)
        AND char_length(regexp_replace(title, U&'[\+010000-\+10FFFF]', 'xx', 'g')) BETWEEN 5 AND 200),
    CONSTRAINT ck_article_body CHECK (body = btrim(body)
        AND char_length(regexp_replace(body, U&'[\+010000-\+10FFFF]', 'xx', 'g')) BETWEEN 10 AND 20000),
    CONSTRAINT ck_article_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_article_publication CHECK (
        (status = 'DRAFT' AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR status = 'ARCHIVED')
);
CREATE INDEX ix_article_admin_updated ON knowledge_article(updated_at DESC, id DESC);
CREATE INDEX ix_article_status_updated ON knowledge_article(status, updated_at DESC, id DESC);
CREATE INDEX ix_article_public_published ON knowledge_article(published_at DESC, id DESC) WHERE status = 'PUBLISHED';
