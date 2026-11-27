# Versions

Canonical stack and runtime pins for the SG Algo Desk monorepo.

## Applications

| App | Artifact / package | Version |
|-----|--------------------|---------|
| Backend API | `com.sgalgodesk:sg-algo-desk` (`com.sgalgodesk.desk`) | `0.0.1-SNAPSHOT` |
| Frontend SPA | `frontend` (`package.json`) | `0.0.0` |

## Backend stack

| Component | Version / note |
|-----------|----------------|
| Java | 25 |
| Spring Boot | 4.0.0 |
| Spring WebFlux | via Boot 4 |
| Spring Security | 7 (via Boot 4) |
| Spring Data R2DBC | via Boot 4 |
| Flyway | via `spring-boot-starter-flyway` + `flyway-database-postgresql` |
| Jackson | 3 (Boot 4 default `tools.jackson`) |
| Lombok | provided (annotation processor) |
| PostgreSQL driver | `r2dbc-postgresql` + JDBC `postgresql` (Flyway) |

## Frontend stack

| Component | Declared |
|-----------|----------|
| React | ^19.3.1 |
| react-dom | ^19.3.1 |
| Vite | ^8.3.6 |
| Tailwind CSS | ^4.3.5 |
| `@tailwindcss/vite` | ^4.3.5 |
| TypeScript | ~7.0.2 |
| oxlint | ^1.90.0 |
| `@vitejs/plugin-react` | ^6.1.2 |

## Runtime

| Item | Value |
|------|-------|
| API port | `8080` |
| SPA port | `5173` (Vite) |
| Postgres | `localhost:5433` |
| Schema | `sg_algo_desk` (`DB_SCHEMA`) |
| Session cookie | `STRATEGY_SESSION` |
| Active broker | MStock only |
| MStock session expiry | next midnight IST |
