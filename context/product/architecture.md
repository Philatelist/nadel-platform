# System Architecture Overview: Nadel

- **Status:** Accepted (documents decisions already made; not a redesign)
- **Sources of truth:** `CLAUDE.md`, `docs/adr/0001`–`0009`, `docs/domain/money-rules.md`, `api/openapi.yaml`,
  `backend/pom.xml`, `infra/docker-compose.yml`. When this document and those sources disagree, the sources win
  and this document gets fixed.
- **Legend:** **[in code]** means the repository already has it (cited). **[planned]** means it has been decided but
  not added yet. **[to decide]** means the decision is still open and needs its own ADR. Section 7 lists every place
  where the brief and the code disagree.

---

## 1. Application & Technology Stack

### Backend

- **Language:** Java 25 **[in code]**: `<java.version>25</java.version>` in `backend/pom.xml`. CI uses Temurin 25 (`.github/workflows/ci.yml`).
- **Framework:** Spring Boot 4.1 (currently 4.1.1) **[in code]**: parent POM in `backend/pom.xml`.
  - Starters: webmvc, validation, data-jpa, flyway, actuator.
  - Virtual threads are on (`backend/src/main/resources/application.yml`). Problem Details come from the `problem` module's own exception handler.
- **Architecture style:** a modular monolith built with Spring Modulith 2.1.1 **[in code]**. See ADR 0001; boundaries are verified by `backend/src/test/java/app/nadel/ModularityTests.java`.
  - Each top-level package `app.nadel.*` is a module.
  - Modules talk only through their public root package or through domain events. `internal` subpackages are closed.
  - A module never reads another module's tables.
  - Modules in code: `tenancy`, `identity`, `plots`, `members`, `audit`, `problem`. Each has a README next to its `package-info.java` (`backend/src/main/java/app/nadel/<module>/README.md`).
  - Modules planned, in roadmap order: `tariffs`, `meters`, `ledger`, `payments`, `documents`, `notifications`.
  - The `plots` module keeps plots as records from phase 1; polygon import, coordinate conversion and the map view arrive in roadmap phase 6. `plot.boundary` is nullable, and every feature before phase 6 must work for plots without a polygon.
- **Security:** Spring Security as an OAuth2 JWT resource server **[in code]**.
  - Starter: `spring-boot-starter-security-oauth2-resource-server` (the Boot 4 name). The issuer URI is in `application.yml`.
  - Authorization uses `@PreAuthorize` at service or controller level.
- **Persistence:** Spring Data JPA, Hibernate and Hibernate Spatial **[in code]**: `backend/pom.xml`. `ddl-auto: validate` and `open-in-view: false`.
- **Build:** Maven through the wrapper **[in code]**: `backend/mvnw`. The root `Makefile` drives everything (`make check` = what CI runs).

### API contract

- **Contract-first OpenAPI 3.1** **[in code]**: `api/openapi.yaml` (`openapi: 3.1.0`), served under `/api/v1` with bearer auth. See ADR 0004.
- **Server code generation:** `openapi-generator-maven-plugin` 7.26.0 **[in code]**.
  - Settings: `spring` generator, `interfaceOnly`, `useSpringBoot4`, `useJackson3`.
  - Output goes to the `nadel.contract.api` and `nadel.contract.model` packages, outside `app.nadel`, so the generated code is not a Modulith module. It is also excluded from JaCoCo.
- **Client code generation:** orval 8.43.0 **[in code]**. It generates a typed fetch client and TanStack Query hooks in `packages/api-client/src/generated` (mutator `nadelFetch`). CI regenerates the client and fails if it differs from the committed one.
- **Errors:** every error is `application/problem+json` with a stable `code` and optional `errors[{field, code, message}]` **[in code]**: `Problem` schema in `api/openapi.yaml`, module `problem`. Texts never contain personal data, SQL or stack traces; clients map `code` to i18n messages and never show server text.
- **Money on the wire:** a string matching `^-?\d+\.\d{2}$` **[in code]** (`api/openapi.yaml`). Clients display amounts and never compute them.

### Web client

- **React 19, Vite 8, TypeScript 6** **[in code]**: `web/package.json`.
- **Libraries** **[in code]**:
  - Tailwind CSS 4 via `@tailwindcss/vite`
  - TanStack Query 5
  - react-router 7
  - MapLibre GL 6
  - `oidc-client-ts` 3.5 with `react-oidc-context` 3.3 (`web/src/auth.ts`)
- **One app for both audiences:** the member account and the admin screens live in one app, and the role decides what is shown.

### Mobile client

- **Expo 58 and React Native 0.88** **[in code]**: `mobile/package.json`, `mobile/app.json`. The scheme is `nadel` and the bundle id is `app.nadel.mobile`. See ADR 0005.
- **Expo Router** **[planned, phase 5]**: not installed yet (see 7.1).
- **EAS Build and EAS Update (OTA)** **[planned]**: there is no `eas.json` yet.

### Shared TypeScript packages

- **pnpm monorepo** **[in code]**: pnpm 10.18 and Node ≥ 22. Workspaces are `web`, `mobile` and `packages/*` (`pnpm-workspace.yaml`).
- **`@nadel/api-client`**: generated code; never edit it by hand.
- **`@nadel/shared`**: money formatting, dates, plot statuses, and i18n catalogs (`uk` is the default, `en` is optional) in `packages/shared/src/i18n`.
- **`@nadel/ui-tokens`**: design tokens shared by web and mobile. UI components themselves are not shared.

---

## 2. Data & Persistence

- **Primary database:** PostgreSQL 17 with PostGIS 3.5 **[in code]**.
  - Image `postgis/postgis:17-3.5`, used both in `infra/docker-compose.yml` and in Testcontainers (`backend/src/test/java/app/nadel/IntegrationTest.java`).
  - Plot and common-land polygons are stored as PostGIS geometry.
- **Schema migrations:** Flyway **[in code]** (`spring-boot-starter-flyway`, `flyway-database-postgresql`).
  - Location: `backend/src/main/resources/db/migration/V{N}__name.sql`. The first one is `V1__foundation.sql`.
  - A migration is never edited after merge.
  - Migrations must be backward-compatible (expand/contract) so the previous release keeps running during a deploy.
  - Demo data is a repeatable script, `db/demo/R__demo_data.sql`, loaded only in the dev profile.
- **Multi-tenancy:** shared schema with `tenant_id` and Row Level Security **[in code]**. See ADR 0002.
  - Every tenant table has `tenant_id uuid not null`, RLS enabled, a `tenant_isolation` policy (`tenant_id = current_tenant_id()`) and an index on `tenant_id`.
  - Two database roles (`infra/postgres/init/01-roles.sql`):
    - The application connects as `nadel_app`, which has `NOBYPASSRLS`.
    - Flyway runs as `nadel_owner`.
  - `tenancy/internal/TenantAwareTransactionManager` runs `set_config('app.tenant_id', <claim>, true)` at the start of every transaction. The setting is scoped to the transaction, so pooled connections are safe.
  - Data queries never run outside a transaction.
  - `tenant_id` comes only from the JWT `tenant_id` claim, never from a request.
  - Every new table needs an isolation test. The reference test is `plots/internal/TenantIsolationTests.java`.
- **Money model:** see ADR 0006 and `docs/domain/money-rules.md`.
  - Types: `BigDecimal` through the `Money` value object in Java, `numeric(14,2)` in the database. Rounding is `HALF_UP` to 2 places and happens only in `Money`.
  - The ledger tables `charge`, `payment` and `allocation` are append-only **[planned, phase 2–3]**. `nadel_app` will be denied `UPDATE`/`DELETE` on them.
  - Balances are always calculated, never stored.
  - Corrections are a `REVERSAL` plus a new entry, or an `ADJUSTMENT` in the current open period. A closed period never changes.
  - The `plot_status` map-color cache **[in code, `V1__foundation.sql`]** can be rebuilt from the ledger at any time.
- **Object storage (invoice PDFs, meter photos):** S3-compatible **[to decide, phase 4]**.
  - The product gets its own ADR. MinIO stopped publishing community images.
  - Candidates: Garage, SeaweedFS, or managed S3 from the hosting provider.
  - The backend should use only the S3 API, so the product can be swapped later.
- **Event outbox:** the Spring Modulith Event Publication Registry (`spring-modulith-starter-jpa`) **[in code, since spec 001]**. Domain events are stored in the same transaction as the change that raised them and redelivered until their listeners finish; outstanding ones are resubmitted at startup, completed ones are deleted. All cross-module side effects (notifications, recalculations, status cache refresh) go through it. See ADR 0007.
  - The table `event_publication` (`V3__audit_and_event_publication.sql`) is the one table without `tenant_id` and RLS: startup resubmission runs without a tenant. Listeners bind the event's `tenantId` with `TenantContext.runAs` before opening their transaction (ADR 0007 implementation notes).
- **Background jobs:** JobRunr with PostgreSQL storage **[planned, with period closing and notifications]**. It runs scheduled and retryable jobs: period-close runs, invoice generation, delivery retries to external channels. It is not the event outbox. See ADR 0007.
- **Cash receipts:** a cash payment carries a receipt number from a per-cooperative counter **[planned, phase 3]**. The counter row is locked in the same transaction as the payment insert, so numbers are sequential, never reused and never changed; a reversed cash payment keeps its receipt number in history.
- **Caching:** none beyond `plot_status` **[assumption]**. The load is small (tens to hundreds of cooperatives), so Redis is not justified.

---

## 3. Identity & Security

- **Identity provider:** Keycloak 26 (currently 26.8.1), realm `nadel` **[in code]**. See ADR 0003, `infra/keycloak/nadel-realm.json`.
  - Clients:
    - `nadel-web`: public, standard flow only, PKCE S256.
    - `nadel-mobile`: public, PKCE S256, redirects `nadel://*` and `exp://*`.
  - Realm roles: only `PLATFORM_OPERATOR` **[in code]** (`identity/Roles.PLATFORM_OPERATOR`). Cooperative roles live in the database (see Authorization). Test users keep email, `emailVerified: true` and `tenant_id`; after a realm change the local stack is recreated with `docker compose down -v` (`infra/README.md`).
  - Compose has a Keycloak healthcheck (management port, `/health/ready`), so `make infra` returns only when Keycloak is ready.
  - The user attribute `tenant_id` is mapped into the access token by an `oidc-usermodel-attribute-mapper` on both clients.
  - Later: Keycloak Organizations for people in several cooperatives, with cooperative selection at sign-in **[planned, separate ADR]**.
- **Mobile OIDC library:** not chosen yet **[assumption: `expo-auth-session` with PKCE]**. It is not in `mobile/package.json`.
- **Authentication flow** **[in code, since spec 001]** (`identity/internal/MemberAuthenticationConverter`, see the `identity` README):
  1. A token with the realm role `PLATFORM_OPERATOR` becomes the operator, with no database access and no permissions (ADR 0008).
  2. Otherwise the `tenant_id` claim is bound with `TenantContext.runAs` and the active member of that cooperative is looked up: by Keycloak subject; on first sign-in, by email (case-insensitive) only when `email_verified` is true and the member is not yet linked, and the subject is then stored on the member. A member linked to another subject is never relinked; archived members are never found.
  3. No member: 403 `MEMBER_NOT_LINKED`. A failure of the lookup itself is 500, not 401.
  4. The union of the member's role permissions becomes the `PERM_*` authorities. Nothing is cached, so a role change applies on the next request.
- **Authorization:** always on the server, with `@PreAuthorize("hasAuthority('PERM_...')")` **[in code, since spec 001]**. Clients hide UI by permission only for convenience (web: `useCan`, `RequirePermission`). See ADR 0009:
  - A fixed list of permissions is defined in code (`identity.Permission`: `MEMBERS_VIEW`, `MEMBERS_EDIT`, `ROLES_MANAGE`, `AUDIT_VIEW`, `PLOTS_VIEW`, `PLOTS_EDIT`, `OWNERSHIP_EDIT`, `IMPORT_RUN`; later features add theirs, such as `PAYMENTS_ALLOCATE`).
  - Roles are named permission sets stored per cooperative (`role`, `role_permission`, `member_role`, tenant-scoped with RLS). Base roles `CHAIRMAN`, `TREASURER`, `MEMBER` are seeded by `seed_base_roles` and cannot be renamed or deleted; Chairman always has every permission; at least one active chairman must remain. Custom roles (for example "Secretary", or "Electrician" for meters) are created by anyone with `ROLES_MANAGE`.
  - Code checks permissions, never role names. A caller without `MEMBERS_VIEW` gets 404 for any member card but their own, so other members' existence is not revealed.
- **Data visibility for members:** a member sees the number and status color of every plot, but debt amounts, owners and personal data only for plots they own. The API enforces this (for example, `GET /plots` omits `debt` on plots the caller does not own), not just the UI **[planned, phase 1]**.
- **Platform operator:** sees no cooperative rows (no `tenant_id`, so RLS returns nothing). Platform reports use aggregate views without personal or financial detail through a separate read-only role; support access is granted by the chairman for a limited time and audited. See ADR 0008.
- **Audit:** every admin action that changes data is written to the audit log **[in code, `audit` module]**. The changing module publishes an `AuditEvent` in its transaction; a listener writes `audit_entry` through the outbox (idempotent by event id). `nadel_app` has only `SELECT` and `INSERT` on `audit_entry`. Read with `GET /audit` (`AUDIT_VIEW`). So far members, roles and role assignments are audited.
- **Logging hygiene:** names, phone numbers, emails and addresses never appear in logs.
- **Secrets:** only from environment variables. The repository contains only dev values for the local docker-compose (`.env.example`, gitleaks allowlist in `.gitleaks.toml`).

---

## 4. External Services & APIs

- **Email:** a transactional provider **[to decide: Postmark or Amazon SES]**. Locally, a test mail server is planned for phase 4 (comment in `infra/docker-compose.yml`).
- **Messaging:** Telegram Bot API and Viber Bot API **[planned, phase 4]**. Viber charges for business messages, so its terms must be checked before committing.
- **Push notifications:** Expo Push Service, which delivers to APNs and FCM **[planned, phase 5]**.
- **Delivery pattern:** every channel sits behind the `notifications` module. Other modules trigger delivery through domain events and the outbox, never through direct calls.
- **Payments, now:** the NBU payment QR standard printed on invoices, prefilled with amount, account and plot code **[planned, phase 4]**.
- **Payments, later:**
  - Bank APIs (PrivatBank, monobank business) for statement retrieval **[later, out of scope for v1]**.
  - Online acquiring **[later]**.
- **Maps:** MapLibre GL with an OSM-based basemap and no Google keys **[in code for the library; tile source to decide]**. The map feature is roadmap phase 6.
- **Cadastral import (phase 6):** DXF, State Land Cadastre XML exchange files, GeoJSON, KML and zipped Shapefile; coordinate conversion from UCS-2000 Gauss-Kruger zones, SK-63 and local systems to WGS84 on the backend (GeoTools or proj4j) **[planned; library to decide in an ADR]**. Data comes from the cooperative's own land documents or a certified land surveyor, never from scraping third-party cadastral sites.

---

## 5. Infrastructure & Delivery

- **Local development:** docker-compose with PostGIS and Keycloak (port 8180) **[in code]**: `infra/docker-compose.yml`. Run it with `make infra` / `make up`.
- **Hosting:** an EU VM running Docker Compose behind Caddy, which handles automatic TLS **[planned]**.
  - Move to Kubernetes only if load requires it.
  - Where personal data may live must be confirmed with a lawyer before selling to other cooperatives.
  - No Dockerfiles, Caddyfile or deploy scripts exist yet.
- **Backups:** PostgreSQL point-in-time recovery (base backups plus WAL archiving) to object storage separate from the application's own storage **[planned; tool to decide, e.g. pgBackRest or WAL-G]**.
- **Source control:** trunk-based development.
  - Short-lived feature branches, PRs that require green CI, squash merge.
  - Conventional Commits; commits never mention AI.
- **Environments:** defined by deployment, not by branch **[planned]**:
  - a preview per PR
  - staging deployed from `main`
  - production deployed from a release tag, with manual approval
- **Releases:** release-please for versions and the changelog **[planned; not configured yet]**.
- **CI:** GitHub Actions **[in code]** (`.github/workflows/ci.yml`), with these jobs:
  - `backend`: `./mvnw verify`, which runs Testcontainers, `ModularityTests` and the JaCoCo floor (`coverage.minimum` in `backend/pom.xml`, may only go up).
  - `clients`: contract regeneration with a diff check, `format:check`, typecheck, lint and the web build.
  - `security`: gitleaks, `pnpm audit --prod --audit-level high` and OSV-Scanner (pinned version). OSV-Scanner fails on any severity, `pnpm audit` only on high and critical, so `osv-scanner.toml` lists every accepted advisory and `pnpm-workspace.yaml` lists only the accepted high and critical ones, each with a reason.
  - `sast`: Semgrep with the Java, TypeScript and React rule sets.
  - `e2e`: Playwright against the real stack on `localhost` (compose `up --wait`, backend jar in the dev profile, `vite dev`, readiness loops, cached chromium); report, traces and logs are uploaded on failure; 20-minute timeout.
- **Dependency updates:** Dependabot **[in code]** (`.github/dependabot.yml`): Maven, npm and Actions weekly; docker-compose monthly. Versions are pinned exactly.
- **Testing:**
  - Backend **[in code]**: JUnit 5, Spring Security Test, Spring Modulith Test and Testcontainers.
  - Clients: typecheck and lint. There is no client unit test framework yet.
  - Browser E2E **[in code, since spec 001]**: Playwright (chromium only) in `web/e2e/`, run against `make infra` + backend + web with `make e2e` locally (browser installed once with `make e2e-install`) and by the CI `e2e` job. Not part of `make check`.

---

## 6. Observability & Monitoring

- **Health:** Spring Boot Actuator with `health` and `info` exposed and liveness/readiness probes on **[in code]**: `application.yml`.
- **Telemetry:** OpenTelemetry from the backend to a Grafana stack **[planned]**:
  - Loki for logs
  - Prometheus for metrics
  - Tempo for traces
- **Client errors:** Sentry for web and mobile **[planned]**. PII scrubbing must be on, to match the logging rule.
- **Log format:** structured JSON logs with trace IDs and the tenant ID, never personal data **[assumption]**.

---

## 7. Discrepancies and open points

Resolved:

1. **Expo Router:** arrives in phase 5; `mobile/CLAUDE.md` says the same.
2. **Outbox and JobRunr:** Modulith Event Publication Registry is the outbox, JobRunr runs scheduled jobs. ADR 0007.
3. **Operator and cooperative data:** aggregates only, time-boxed audited support access. ADR 0008.
4. **Rounding wording:** `money-rules.md` section 1 and `CLAUDE.md` agree: rounding happens in `Money`, applied when a charge is created.
5. **Ignored advisories:** the lists differ on purpose (OSV fails on every severity, `pnpm audit` only on high); `CLAUDE.md` now says so.
6. **OSV-Scanner version:** pinned in CI.
7. **Base roles:** Chairman, Treasurer and Member. Electrician is no longer a base role (spec 001); an electrician gets a custom role with meter permissions when meters arrive. `CLAUDE.md` still lists `ELECTRICIAN` until the owner updates it.

Open:

8. **Nothing exists for delivery yet.** Dockerfiles, a Caddyfile, a deploy workflow, `eas.json`, release-please and OpenTelemetry/Sentry dependencies are all missing. Everything in sections 5 and 6 marked [planned] is unimplemented.
9. **ADRs still to write:** S3-compatible storage product, email provider, Keycloak Organizations, basemap tile source, backup tooling, data residency.
