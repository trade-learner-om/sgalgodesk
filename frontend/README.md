# Frontend — SG Algo Desk UI

Vite + React 19 + Tailwind v4 SPA. Versions: [../VERSION.md](../VERSION.md). Architecture: [../docs/frontend/IMPLEMENTATION.md](../docs/frontend/IMPLEMENTATION.md).

## Run

Requires the backend on port **8080**.

```bash
npm install
npm run dev
```

Open **http://localhost:5173**. Vite proxies `/api` (including WebSocket) to `http://localhost:8080`.

```bash
npm run build    # tsc -b && vite build
npm run lint     # oxlint
npm run preview  # preview production build
```

## App flow

1. `AuthCard` — email/password signup or login  
2. `BrokerGate` — save MStock API key, login + OTP/TOTP  
3. `AppShell` — Strategy Builder / Running Positions / Strategy Execution  

Session uses cookies (`credentials: 'include'`). The browser never holds the broker access token.

## Layout

- Full-viewport shell (`h-screen`), unified control bar
- Theme: `strategy-desk-theme` in `localStorage`; FOUC script in `index.html`
