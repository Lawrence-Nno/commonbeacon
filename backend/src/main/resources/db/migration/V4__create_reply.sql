CREATE TABLE reply (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL REFERENCES question(id),
    author_id UUID NOT NULL REFERENCES app_user(id),
    body TEXT NOT NULL,
    visibility VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_reply_body CHECK (body = btrim(body) AND char_length(body) BETWEEN 1 AND 20000),
    CONSTRAINT ck_reply_visibility CHECK (visibility IN ('VISIBLE', 'HIDDEN')),
    CONSTRAINT ck_reply_version CHECK (version >= 0)
);
CREATE INDEX ix_reply_question_created_id ON reply(question_id, created_at, id);
