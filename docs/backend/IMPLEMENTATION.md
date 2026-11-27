# Backend implementation

Spring Boot WebFlux API under `com.sgalgodesk.desk` (artifact `com.sgalgodesk:sg-algo-desk`).

## Package map

| Package | Role |
|---------|------|
| `account` | Signup/login/logout, `me`, settings |
| `broker` | `BrokerId.MSTOCK`, credentials/session, active broker |
| `mstock` | Type A auth/API, script master, option chain, market WS binary LTP, primary-IP |
| `instrument` | REST search/underlyings/expiries/benchmarks → MStock instruments |
| `optionchain` | MStock Type A chain |
| `quote` | MStock LTP |
| `order` | Place via MStock Type A orders |
| `position` | Position book seeded from MStock Type B positions (+ trades fallback) |
| `portfoliofeed` | `/api/ws/portfolio` snapshots |
| `indicators` | Candles (MStock Type B historical), Supertrend/EMA |
| `strategy` | Credit-spread suggest |
| `marketfeed` | `/api/ws/market` → MStock WS |
| `config` | `MstockProperties`, `AppProperties`, security, WebClient |
| `web` | `ApiExceptionHandler` → `{ error, status }` |

## Multi-broker note

Desk is **MStock-only**. `broker_credentials` / `broker_session` / `user_settings.active_broker` remain; only `MSTOCK` is supported. Flyway `V7` drops legacy Upstox tables and UPSTOX rows.

## Auth

- App login: `POST /api/account/signup|login`, cookie `STRATEGY_SESSION`
- MStock Retail Type A: `PUT /api/brokers/mstock/credentials`, `POST .../login`, `POST .../session` (OTP) / `.../session/totp`
- Primary IP: `GET /api/brokers/mstock/primary-ip` (config `mstock.primary-ip` or ipify)
- Auth header to MStock: `Authorization: token {api_key}:{access_token}`, `X-Mirae-Version: 1`
- Session expires at next midnight IST
- Product map: app `D` → MStock `NRML`; `I` → `MIS`

## Env

- `DB_URL`, `DB_SCHEMA` (prefer `sg_algo_desk`), `SPRING_R2DBC_USERNAME` / `PASSWORD`
- `FRONTEND_BASE_URL` (CORS)
- `MSTOCK_API_BASE_URL`, `MSTOCK_WS_BASE_URL`, `MSTOCK_PRIMARY_IP` (optional)
