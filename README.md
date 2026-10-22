# Nadel

Payment accounting platform for dacha (garden) cooperatives.

- Map of plots colored by debt status
- Tariffs with an effective date and retroactive recalculation
- Electricity meter readings entered by admins or submitted by members, with a share of transformer losses
- Charges ledger: membership fees, special-purpose fees, electricity, land tax
- Treasurer allocates payments across charge types, with partial payments and prepayments
- Monthly invoices with an NBU payment QR code, delivered by email, Telegram and Viber
- Web account and apps for iOS and Android
- Many cooperatives on one platform with full data isolation

## Stack

| Layer | Technology |
|---|---|
| Backend | Java 25, Spring Boot 4.1, Spring Modulith, Spring Security (JWT), JPA + Hibernate Spatial, Maven |
| Database | PostgreSQL 17 + PostGIS, Flyway, Row Level Security |
| Identity | Keycloak 26 |
| Web | React, Vite, TypeScript, Tailwind CSS, TanStack Query, MapLibre GL, oidc-client-ts |
| Mobile | Expo (React Native) |
| Contract | OpenAPI 3.1 → Spring interfaces (openapi-generator) and TS client (orval) |

## Quick start

Requirements: Docker, JDK 25, Node 22+, pnpm 10.

```bash
cp .env.example .env    # optional: defaults already match local docker-compose
make install            # client dependencies
make up                 # Postgres + PostGIS, Keycloak, API on :8080, web on :5173
```

`make help` lists all targets; `make check` runs everything CI runs.

Browser end-to-end specs (Playwright, chromium) run against the real local stack and are not part of `make check`:

```bash
make e2e-install        # once: download the chromium browser for Playwright
make e2e                # starts infra, the backend jar and web, runs web/e2e, then stops backend and web
```

`make e2e` needs JDK 25 and free ports 8080 and 5173; it runs against a throwaway database `nadel_e2e` (dropped afterwards), never your demo `nadel` database; logs go to `backend/target/e2e-*.log`. CI runs the same specs in the `e2e` job.

Sign in as `treasurer@demo.local` / `nadel`. Other test users are listed in [CLAUDE.md](CLAUDE.md). Each test user is
linked to a demo member by email; what they may do comes from that member's roles in the database (Chairman,
Treasurer, Member and custom roles), not from Keycloak. After a change to the Keycloak realm, recreate the local stack
with `docker compose -f infra/docker-compose.yml down -v` (see [infra/README.md](infra/README.md)).

| Service | Address |
|---|---|
| API | http://localhost:8080/api/v1 |
| Keycloak | http://localhost:8180 (admin / admin) |

## Documentation

- [CLAUDE.md](CLAUDE.md) — development rules for people and agents
- [docs/domain/money-rules.md](docs/domain/money-rules.md) — charging and payment allocation rules
- [docs/domain/glossary.md](docs/domain/glossary.md) — glossary
- [docs/adr](docs/adr) — architecture decision records
- [docs/features](docs/features) — feature specs
- [docs/roadmap.md](docs/roadmap.md) — roadmap
