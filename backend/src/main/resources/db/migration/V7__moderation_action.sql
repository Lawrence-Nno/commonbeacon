CREATE TABLE moderation_action (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    question_id UUID REFERENCES question(id),
    reply_id UUID REFERENCES reply(id),
    action VARCHAR(20) NOT NULL CHECK (action IN ('HIDE', 'RESTORE')),
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_moderation_action_target CHECK ((question_id IS NOT NULL) <> (reply_id IS NOT NULL)),
    CONSTRAINT ck_moderation_action_reason CHECK (reason = btrim(reason)
        AND char_length(regexp_replace(reason, U&'[\+010000-\+10FFFF]', 'xx', 'g')) BETWEEN 5 AND 2000)
);
CREATE INDEX ix_moderation_action_question ON moderation_action(question_id, created_at, id) WHERE question_id IS NOT NULL;
CREATE INDEX ix_moderation_action_reply ON moderation_action(reply_id, created_at, id) WHERE reply_id IS NOT NULL;
