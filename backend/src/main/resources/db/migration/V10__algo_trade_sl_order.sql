ALTER TABLE algo_strategy_trade
    ADD COLUMN IF NOT EXISTS sl_order_id VARCHAR(128);

ALTER TABLE algo_strategy_trade
    ADD COLUMN IF NOT EXISTS sl_trigger_price DOUBLE PRECISION;
