CREATE TABLE question (
    id UUID PRIMARY KEY,
    board_id UUID NOT NULL REFERENCES board(id),
    author_id UUID NOT NULL REFERENCES app_user(id),
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    visibility VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_question_title CHECK (title = btrim(title) AND char_length(title) BETWEEN 5 AND 200),
    CONSTRAINT ck_question_body CHECK (body = btrim(body) AND char_length(body) BETWEEN 10 AND 20000),
    CONSTRAINT ck_question_visibility CHECK (visibility IN ('VISIBLE', 'HIDDEN')),
    CONSTRAINT ck_question_version CHECK (version >= 0)
);
CREATE INDEX ix_question_board_created_id ON question(board_id, created_at DESC, id DESC);
