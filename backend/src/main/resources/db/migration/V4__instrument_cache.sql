CREATE TABLE instrument_cache (
    as_of_date        DATE PRIMARY KEY,
    payload           BYTEA NOT NULL,
    instrument_count  INT NOT NULL,
    loaded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
