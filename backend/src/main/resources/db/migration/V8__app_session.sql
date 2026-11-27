CREATE TABLE app_session (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    device_id     UUID NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_app_session_device UNIQUE (device_id)
);

CREATE INDEX idx_app_session_user ON app_session (user_id);
