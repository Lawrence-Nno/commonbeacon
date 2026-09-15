CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_email_normalized CHECK (
        email = lower(btrim(email)) AND char_length(email) BETWEEN 3 AND 254
    ),
    CONSTRAINT ck_app_user_display_name CHECK (
        display_name = btrim(display_name) AND char_length(display_name) BETWEEN 1 AND 80
    ),
    CONSTRAINT ck_app_user_password_hash CHECK (char_length(btrim(password_hash)) > 0),
    CONSTRAINT ck_app_user_role CHECK (role IN ('MEMBER', 'MODERATOR', 'ADMINISTRATOR'))
);
