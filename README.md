# SG Algo Desk

Analysis desk for index, stock, and commodity futures and options. Sign up with email and password, connect an MStock Trading API account, build multi-leg strategies, and inspect expiry plus T0 payoff with live LTPs.

Stack versions are pinned in [VERSION.md](VERSION.md).

## Stack

- **Backend:** Java 25, Spring Boot 4 WebFlux, R2DBC, Flyway, Jackson 3, Lombok, Spring Security 7
- **Frontend:** Vite, React 19, TypeScript, Tailwind CSS v4
- **Database:** PostgreSQL on port `5433`, schema `sg_algo_desk`

## Prerequisites

- Java 25
- Node.js 20+
- PostgreSQL listening on `localhost:5433`
- An MStock Trading API key from [trade.mstock.com](https://trade.mstock.com) (register the desk egress IP as Primary/Static IP)

Create the database once:

```bash
createdb -h localhost -p 5433 -U postgres tradingdb
```

## Configuration

```bash
export SPRING_R2DBC_USERNAME=readwrite
export SPRING_R2DBC_PASSWORD=your-postgres-password
export DB_URL=postgresql://localhost:5433/tradingdb
export DB_SCHEMA=sg_algo_desk
export FRONTEND_BASE_URL=http://localhost:5173
# Optional fixed egress IP shown in the UI (otherwise detected via ipify)
# export MSTOCK_PRIMARY_IP=x.x.x.x
```

Defaults live in `backend/src/main/resources/application.yml`.

## Run

Terminal 1 — API:

```bash
cd backend
./mvnw spring-boot:run
```

Terminal 2 — UI:

```bash
cd frontend
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173).

## First-time flow

1. Sign up / log in (email + password, cookie session `STRATEGY_SESSION`)
2. Save MStock API key (paste the shown Primary IP into trade.mstock.com → Trading APIs)
3. Log in with client code + password, complete OTP/TOTP (session valid until midnight IST)
4. Pick All / Indices / Stocks / Commodities, search an underlying, select expiry, click **B** / **S** on the chain
5. Inspect payoff (scroll to zoom, hover for PnL), edit lots/avg on active legs
6. Use **Brokers** in the header to reconnect or remove the MStock account

## Docs

- [docs/backend/IMPLEMENTATION.md](docs/backend/IMPLEMENTATION.md)
- [docs/frontend/IMPLEMENTATION.md](docs/frontend/IMPLEMENTATION.md)
