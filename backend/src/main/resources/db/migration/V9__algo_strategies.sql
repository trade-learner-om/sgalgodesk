CREATE TABLE algo_strategy (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name            VARCHAR(160) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'SAVED',
    execution_mode  VARCHAR(16) NOT NULL DEFAULT 'PAPER',
    config_json     TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_algo_strategy_user ON algo_strategy (user_id, updated_at DESC);

CREATE TABLE algo_strategy_run (
    id              UUID PRIMARY KEY,
    strategy_id     UUID NOT NULL REFERENCES algo_strategy (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    run_date        DATE NOT NULL,
    execution_mode  VARCHAR(16) NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL,
    realised_pnl    DOUBLE PRECISION NOT NULL DEFAULT 0,
    unrealised_pnl  DOUBLE PRECISION NOT NULL DEFAULT 0,
    reentry_count   INTEGER NOT NULL DEFAULT 0,
    trail_floor_pnl DOUBLE PRECISION,
    peak_pnl        DOUBLE PRECISION NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at        TIMESTAMPTZ
);

CREATE INDEX idx_algo_strategy_run_strategy ON algo_strategy_run (strategy_id, started_at DESC);

CREATE TABLE algo_strategy_trade (
    id              UUID PRIMARY KEY,
    strategy_id     UUID NOT NULL REFERENCES algo_strategy (id) ON DELETE CASCADE,
    run_id          UUID NOT NULL REFERENCES algo_strategy_run (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    leg_index       INTEGER NOT NULL,
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
    executed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at       TIMESTAMPTZ
);

CREATE INDEX idx_algo_strategy_trade_strategy ON algo_strategy_trade (strategy_id, executed_at DESC);
CREATE INDEX idx_algo_strategy_trade_run ON algo_strategy_trade (run_id, leg_index);

CREATE TABLE algo_strategy_event (
    id              UUID PRIMARY KEY,
    strategy_id     UUID NOT NULL REFERENCES algo_strategy (id) ON DELETE CASCADE,
    run_id          UUID REFERENCES algo_strategy_run (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    event_type      VARCHAR(48) NOT NULL,
    message         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_algo_strategy_event_strategy ON algo_strategy_event (strategy_id, created_at DESC);
