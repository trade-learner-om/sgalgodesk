CREATE TABLE upstox_credentials (
    user_id        UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    client_id      VARCHAR(128) NOT NULL,
    client_secret  TEXT NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
