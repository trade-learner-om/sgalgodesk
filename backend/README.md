# Backend — SG Algo Desk API

Spring Boot 4 WebFlux service (`com.sgalgodesk:sg-algo-desk`). See [../VERSION.md](../VERSION.md) for pins and [../docs/backend/IMPLEMENTATION.md](../docs/backend/IMPLEMENTATION.md) for architecture.

## Run

```bash
export SPRING_R2DBC_USERNAME=readwrite
export SPRING_R2DBC_PASSWORD=your-postgres-password
export DB_URL=postgresql://localhost:5433/tradingdb
export DB_SCHEMA=sg_algo_desk

./mvnw spring-boot:run
```

Listens on **http://localhost:8080**. Flyway applies migrations into schema **`sg_algo_desk`**.

## Packages

| Package | Role |
|---------|------|
| `account` | Signup / login / logout / me |
| `broker` / `mstock` | MStock credentials, OTP session, API client |
| `instrument` | Script-master search, underlyings, expiries, benchmarks |
| `optionchain` | MStock option chain |
| `quote` | REST LTP |
| `marketfeed` | Browser WS ↔ MStock market feed |
| `position` / `portfoliofeed` | Position book + portfolio WS snapshots |
| `order` | Place orders |
| `indicators` / `strategy` | Candles, Supertrend/EMA, credit-spread suggest |
| `domain` / `repository` | R2DBC entities |
| `config` / `security` / `web` | Security, WebClient, errors |

## Key endpoints

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/account/signup`, `/login` | Public; sets `STRATEGY_SESSION` |
| GET | `/api/account/me` | Profile + MStock link status |
| PUT | `/api/brokers/mstock/credentials` | API key |
| GET | `/api/brokers/mstock/primary-ip` | Egress IP for Trading APIs whitelist |
| POST | `/api/brokers/mstock/login`, `/session`, `/session/totp` | Type A auth |
| GET | `/api/instruments/*`, `/api/option-chain`, `/api/quotes/*` | Market data |
| WS | `/api/ws/market`, `/api/ws/portfolio` | Live LTP + positions |
