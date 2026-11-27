CREATE TABLE app_user (
    id              UUID PRIMARY KEY,
    email           VARCHAR(320) NOT NULL UNIQUE,
    password_hash   VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE upstox_link (
    user_id             UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    upstox_user_id      VARCHAR(64) NOT NULL,
    user_name           VARCHAR(256) NOT NULL,
    access_token        TEXT NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
