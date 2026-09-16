ALTER TABLE reply ADD CONSTRAINT uk_reply_question_id UNIQUE (question_id, id);
ALTER TABLE question ADD COLUMN accepted_reply_id UUID;
ALTER TABLE question ADD CONSTRAINT fk_question_accepted_reply
    FOREIGN KEY (id, accepted_reply_id) REFERENCES reply(question_id, id);
