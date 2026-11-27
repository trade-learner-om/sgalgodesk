CREATE TABLE dds_strategy (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name            VARCHAR(160) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'SAVED',
    execution_mode  VARCHAR(16) NOT NULL DEFAULT 'PAPER',
    settings_json   TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dds_strategy_user ON dds_strategy (user_id, updated_at DESC);
CREATE INDEX idx_dds_strategy_status ON dds_strategy (status);

CREATE TABLE dds_run (
    id              UUID PRIMARY KEY,
    strategy_id     UUID NOT NULL REFERENCES dds_strategy (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    run_date        DATE NOT NULL,
    execution_mode  VARCHAR(16) NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL,
    realised_pnl    DOUBLE PRECISION NOT NULL DEFAULT 0,
    unrealised_pnl  DOUBLE PRECISION NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at        TIMESTAMPTZ
);

CREATE INDEX idx_dds_run_strategy ON dds_run (strategy_id, started_at DESC);
CREATE INDEX idx_dds_run_strategy_date ON dds_run (strategy_id, run_date DESC);

CREATE TABLE dds_trade (
    id              UUID PRIMARY KEY,
    strategy_id     UUID NOT NULL REFERENCES dds_strategy (id) ON DELETE CASCADE,
    run_id          UUID NOT NULL REFERENCES dds_run (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    leg_role        VARCHAR(8) NOT NULL,
    instrument_key  VARCHAR(128) NOT NULL,
    trading_symbol  VARCHAR(128),
    side            VARCHAR(16) NOT NULL,
    quantity        INTEGER NOT NULL,
    execution_mode  VARCHAR(16) NOT NULL,
    broker_order_id VARCHAR(128),
    entry_price     DOUBLE PRECISION NOT NULL DEFAULT 0,
    exit_price      DOUBLE PRECISION,
    pnl             DOUBLE PRECISION NOT NULL DEFAULT 0,
    trigger_reason  VARCHAR(64) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    delta_at_entry  DOUBLE PRECISION,
    lot_size        INTEGER,
    strike          DOUBLE PRECISION,
    expiry          VARCHAR(32),
    option_type     VARCHAR(8),
    executed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at       TIMESTAMPTZ
);

CREATE INDEX idx_dds_trade_strategy ON dds_trade (strategy_id, executed_at DESC);
CREATE INDEX idx_dds_trade_run ON dds_trade (run_id, executed_at ASC);
CREATE INDEX idx_dds_trade_strategy_status ON dds_trade (strategy_id, status);

CREATE TABLE dds_event (
    id              UUID PRIMARY KEY,
    strategy_id     UUID NOT NULL REFERENCES dds_strategy (id) ON DELETE CASCADE,
    run_id          UUID REFERENCES dds_run (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    event_type      VARCHAR(48) NOT NULL,
    message         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dds_event_strategy ON dds_event (strategy_id, created_at DESC);
