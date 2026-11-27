# Frontend implementation

SPA under `frontend/src` (no router, no global store). Vite proxies `/api` + WS to `http://localhost:8080` (`vite.config.ts`).

## Source map

| Path | Role |
|------|------|
| `main.tsx` | `initTheme()` then `<App />` |
| `App.tsx` | Auth → BrokerGate → `AppShell` |
| `api/client.ts` | Cookie `fetch` wrapper + typed `api` (MStock) |
| `hooks/useTheme.ts` | Light/dark + `localStorage` |
| `hooks/useMarketFeed.ts` | `/api/ws/market` subscribe / reconnect |
| `hooks/usePortfolioFeed.ts` | `/api/ws/portfolio` position snapshots / reconnect |
| `payoff/blackScholes.ts` | BS price / delta / vega |
| `payoff/payoff.ts` | Legs, expiry+T0 PnL, strategy greeks |
| `components/AuthCard.tsx` | Login / signup — brand **SG Algo Desk** |
| `components/BrokerGate.tsx` | MStock API key/OTP; remove account |
| `components/BrokersManageModal.tsx` | Desk overlay to connect / remove MStock |
| `components/MstockPrimaryIpHint.tsx` | Shows backend egress IP for Primary/Static IP |
| `components/AppShell.tsx` | Header + Brokers manage + three tabs |
| `components/StrategyWorkspace.tsx` | Strategy Builder: chain, legs, templates |
| `components/RunningPositions.tsx` | Open/closed positions, payoff, exit |
| `components/StrategyExecution.tsx` | Index signal + credit-spread suggest + live execute |
| `components/PayoffChart.tsx` | SVG payoff, KPIs, zoom, overlay PnL |
| `components/ThemeToggle.tsx` | Theme control |
| `index.css` | Tailwind v4 + `@custom-variant dark` |

## Gate state machine

Desk unlocks when MStock is linked + `tokenValid`. From the desk header, **Brokers** opens a modal to reconnect or remove the account. Token expiry messages use `broker_token_expired` (WS close `1008` / `4401`).

## App shell tabs

1. **Strategy Builder**
2. **Running Positions** — live `/api/ws/portfolio` snapshots
3. **Strategy Execution** — index signal + credit-spread execute

## API client

Notable methods: `me`, `signup`/`login`/`logout`, `setActiveBroker`, `saveMstockApiKey`, `mstockPrimaryIp`, `mstockLogin`, `mstockSessionOtp`/`Totp`, instruments/chain/quotes/positions/orders/indicators.
