CREATE TABLE board (
    id UUID PRIMARY KEY,
    slug VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_board_slug UNIQUE (slug),
    CONSTRAINT ck_board_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_board_name CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 120),
    CONSTRAINT ck_board_description CHECK (description = btrim(description) AND char_length(description) BETWEEN 1 AND 2000),
    CONSTRAINT ck_board_version CHECK (version >= 0)
);
