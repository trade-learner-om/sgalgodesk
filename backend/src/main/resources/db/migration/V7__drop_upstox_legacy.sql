-- Remove legacy Upstox-only tables/rows; default active broker to MStock.

DELETE FROM broker_session WHERE broker = 'UPSTOX';
DELETE FROM broker_credentials WHERE broker = 'UPSTOX';

UPDATE user_settings
SET active_broker = 'MSTOCK'
WHERE active_broker IS DISTINCT FROM 'MSTOCK';

DROP TABLE IF EXISTS upstox_credentials;
DROP TABLE IF EXISTS user_session;
