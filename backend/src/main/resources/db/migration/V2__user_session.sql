CREATE TABLE user_session (
    user_id             UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    upstox_user_id      VARCHAR(64) NOT NULL,
    user_name           VARCHAR(256) NOT NULL,
    api_key             TEXT NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'upstox_link'
    ) THEN
        INSERT INTO user_session (user_id, upstox_user_id, user_name, api_key, expires_at, updated_at)
        SELECT user_id, upstox_user_id, user_name, access_token, expires_at, updated_at
        FROM upstox_link
        ON CONFLICT (user_id) DO NOTHING;
    END IF;
END $$;

DROP TABLE IF EXISTS upstox_link;
