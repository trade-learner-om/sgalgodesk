CREATE TABLE broker_credentials (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    broker          VARCHAR(16) NOT NULL,
    client_id       VARCHAR(256) NOT NULL,
    client_secret   TEXT,
    extra_json      TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_broker_credentials_user_broker UNIQUE (user_id, broker)
);

CREATE TABLE broker_session (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    broker          VARCHAR(16) NOT NULL,
    broker_user_id  VARCHAR(64) NOT NULL,
    display_name    VARCHAR(256) NOT NULL,
    access_token    TEXT NOT NULL,
    refresh_meta    TEXT,
    expires_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_broker_session_user_broker UNIQUE (user_id, broker)
);

ALTER TABLE user_settings
    ADD COLUMN IF NOT EXISTS active_broker VARCHAR(16);

INSERT INTO broker_credentials (id, user_id, broker, client_id, client_secret, extra_json, updated_at)
SELECT gen_random_uuid(), user_id, 'UPSTOX', client_id, client_secret, NULL, updated_at
FROM upstox_credentials
ON CONFLICT (user_id, broker) DO NOTHING;

INSERT INTO broker_session (id, user_id, broker, broker_user_id, display_name, access_token, refresh_meta, expires_at, updated_at)
SELECT gen_random_uuid(), user_id, 'UPSTOX', upstox_user_id, user_name, api_key, NULL, expires_at, updated_at
FROM user_session
ON CONFLICT (user_id, broker) DO NOTHING;

UPDATE user_settings us
SET active_broker = 'UPSTOX'
WHERE us.active_broker IS NULL
  AND EXISTS (SELECT 1 FROM broker_session bs WHERE bs.user_id = us.user_id AND bs.broker = 'UPSTOX');

INSERT INTO user_settings (user_id, preferred_underlying_key, preferred_underlying_symbol, preferred_asset_class, active_broker, updated_at)
SELECT bs.user_id, NULL, NULL, NULL, 'UPSTOX', NOW()
FROM broker_session bs
WHERE bs.broker = 'UPSTOX'
  AND NOT EXISTS (SELECT 1 FROM user_settings us WHERE us.user_id = bs.user_id)
ON CONFLICT (user_id) DO NOTHING;
