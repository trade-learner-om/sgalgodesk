CREATE TABLE user_settings (
    user_id                       UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    preferred_underlying_key      VARCHAR(128),
    preferred_underlying_symbol   VARCHAR(64),
    preferred_asset_class         VARCHAR(32),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
