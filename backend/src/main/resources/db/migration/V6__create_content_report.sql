CREATE TABLE content_report (
    id UUID PRIMARY KEY,
    reporter_id UUID NOT NULL REFERENCES app_user(id),
    question_id UUID REFERENCES question(id),
    reply_id UUID REFERENCES reply(id),
    reason TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    resolver_id UUID REFERENCES app_user(id),
    resolved_at TIMESTAMPTZ,
    resolution_decision VARCHAR(30),
    resolution_note TEXT,
    CONSTRAINT ck_report_target CHECK ((question_id IS NOT NULL) <> (reply_id IS NOT NULL)),
    -- Replace supplementary code points with two ASCII characters to match Java/JS UTF-16 bounds.
    CONSTRAINT ck_report_reason CHECK (reason = btrim(reason)
        AND char_length(regexp_replace(reason, U&'[\+010000-\+10FFFF]', 'xx', 'g')) BETWEEN 5 AND 2000),
    CONSTRAINT ck_report_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_report_resolution CHECK (
        (status = 'OPEN' AND resolver_id IS NULL AND resolved_at IS NULL
            AND resolution_decision IS NULL AND resolution_note IS NULL)
        OR (status = 'RESOLVED' AND resolver_id IS NOT NULL AND resolved_at IS NOT NULL
            AND resolution_decision IS NOT NULL AND resolution_note IS NOT NULL
            AND resolution_decision IN ('DISMISS', 'HIDE', 'ACKNOWLEDGE_HIDDEN')
            AND resolution_note = btrim(resolution_note)
            AND char_length(regexp_replace(resolution_note, U&'[\+010000-\+10FFFF]', 'xx', 'g')) BETWEEN 5 AND 2000))
);
CREATE UNIQUE INDEX ux_report_open_question ON content_report(reporter_id, question_id)
    WHERE status = 'OPEN' AND question_id IS NOT NULL;
CREATE UNIQUE INDEX ux_report_open_reply ON content_report(reporter_id, reply_id)
    WHERE status = 'OPEN' AND reply_id IS NOT NULL;
CREATE INDEX ix_report_status_created_id ON content_report(status, created_at, id);
