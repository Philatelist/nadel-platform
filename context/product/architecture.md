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
  - Modules in code: `tenancy`, `identity`, `plots`, `members`, `audit`, `problem`, `settings` (cooperative settings, since spec 007), `imports` (Excel import, since spec 008), `xlsx` (the workbook safety guard `WorkbookGuard` shared by the Excel import and report templates; no dependencies, since spec 013), `money` (`Money` and `Rate` value objects, since spec 009, ADR 0013), `tariffs` (charge types, tariffs with versions, scopes and the expected amount; since spec 009), `meters` (meters, the electricity meter tree, readings and consumption; since spec 010, ADR 0014), `ledger` (charges,
    billing months, the month run, charges by readings, losses and common consumer runs, the "not charged" queue,
    reversals, opening balances and the plot account; since spec 011), `reports` (built-in reports and report templates; since spec 013, ADR 0015), `privacy` (encryption of personal data at rest: `PersonalDataCipher`, blind indexes, per-cooperative keys in `tenant_data_key` wrapped by the master key `NADEL_PD_MASTER_KEY`, the start-up key-check and master key rotation as a Flyway callback, `OwnerKeyring` for owner-side Flyway work; depends on `tenancy` and `problem`; since spec 014, ADR 0016). Each has a README next to its `package-info.java` (`backend/src/main/java/app/nadel/<module>/README.md`).
  - Dependencies between modules (feature 007): `plots` -> `members` (`MemberDirectory`, names and row locks; and it implements `members.MemberArchiveGuard`), `plots` -> `settings` (`CooperativeSettings`), `plots` -> `identity` (it implements `identity.OwnedPlotsSource`, so `identity` never depends on `plots`), `members`/`plots`/`settings` -> `audit` (events) and `problem`. Ownership lives inside `plots`; vetoes and reads across modules are synchronous SPIs, see ADR 0011.
  - Dependencies of `tariffs` (feature 009): `tariffs` -> `plots` (the public read facade `PlotCatalog`: `find`, `findAll`, `inNaturalOrder`, `countChargeable`; plain `PlotFacts` records, so `tariffs` never reads the `plot` table), `identity` (`CurrentUser`, `AccessProfile`, `OwnedPlotsSource`), `money` (`Money`, `Rate`), `audit` (events) and `problem`. `plots` never depends on `tariffs` (no cycle: the plot card loads its tariffs section with its own request); `ledger` (feature 011) depends on `tariffs` (the read facades `ChargeTypeCatalog` and `TariffBook`, the pure `TariffResolver`, `ChargeCalculator.breakdown` and `DaySet`) and implements its port `TariffVersionUsage`. The module owns `charge_type`, `tariff`, `tariff_version` and `tariff_scope_plot` (V10); the five charge types of a cooperative (`COMMON_CONSUMERS` added by V13) are seeded by `seed_charge_types`, which `seed_base_roles` calls.
  - Dependencies of `privacy` (feature 014, ADR 0016): `members`, `audit` and `imports` -> `privacy` (`PersonalDataCipher` at runtime; `OwnerKeyring` in their Java migrations), `privacy` -> `tenancy` (`TenantContext`) and `problem`. `privacy` depends on none of the modules that use it.
  - What `tariffs` offers (feature 009): *charge types* (five seeded: membership, electricity, land tax, water and, since feature 011, common consumers; custom ones; archived, never deleted), *tariffs* of a charge type with *versions* by date (a change of rate is a new version; a version can be corrected or deleted until a charge uses it, `PUT`/`DELETE /tariffs/{id}/versions/{versionId}`, V11) (basis `PER_PLOT`, `PER_AREA_SOTKA`, `PER_KWH`, `PER_M3`, `FIXED_ONE_TIME`; frequency `MONTHLY`, `QUARTERLY`, `YEARLY`, `ONE_OFF`; rate with up to four decimals), a *scope* per tariff (all plots with exclusions, or selected plots) and the *expected amount* of a plot for a month. Nothing is charged here. Public API of the module root: `ChargeCalculator` (pure, no Spring, no database; the only place that turns versions, area and a month into an amount, `docs/domain/money-rules.md` section 4.1), `VersionTerms`, `Basis`, `Frequency`, `ExpectedCharge` (`Amount` or `ByReadings`), the read facade `ChargeTypeCatalog` (since feature 011) and the port `TariffVersionUsage` (asks whether a charge uses a version; implemented by the ledger of feature 011, so `tariffs` never depends on the ledger). Endpoints (tags `charge-types` and `tariffs`): `GET/POST /charge-types`, `PUT /charge-types/{id}`, `POST /charge-types/{id}/archive`, `PUT /charge-types/order`, `GET/POST /tariffs`, `GET /tariffs/{id}`, `POST /tariffs/{id}/versions`, `PUT /tariffs/{id}/scope` and `GET /plots/{id}/tariffs?month=YYYY-MM`. Reads need `TARIFFS_VIEW`, every write needs `TARIFFS_VIEW` and `TARIFFS_MANAGE`; the expected amount of a plot is also open to a member for a plot they currently own, and any other plot is 404. Details in `backend/src/main/java/app/nadel/tariffs/README.md`.
  - `money` (feature 009, ADR 0013): `Money` (two decimals, the only code in the platform that rounds an amount, `HALF_UP`, in `Money.of` and `Money.ofQuotient`) and `Rate` (a tariff rate: at most four decimals, never rounded, a fifth decimal is rejected). No tables, no endpoints and no dependency on another module, so the ledger and payments can use the types without depending on `tariffs`.
  - Dependencies of `imports` (feature 008, ADR 0012): `imports` -> `plots` (`PlotImport`), `members` (`MemberImport`), `meters` (`MeterImport` since feature 010, `WaterConnectionImport` since feature 011), `ledger` (`OpeningBalanceImport`, feature 011), `identity`, `problem` and `audit` (it publishes `IMPORT_COMMITTED`). The facades `members.MemberImport` and `plots.PlotImport` plan (no writes) and apply an import with the owning module's own rules; nothing depends on `imports`. Spreadsheet library: `org.dhatim:fastexcel` and `fastexcel-reader` 0.20.2 **[in code]**.
  - `meters` (feature 010, ADR 0014; tables `meter`, `meter_parent`, `meter_reading` in V12, all under RLS `tenant_isolation`): the register of electricity and water meters (kinds `PLOT`, `SUBSTATION`, `QUARTER`, `COMMON_CONSUMER`), the dated parent history of the electricity tree, readings taken on any date (statuses `APPROVED`, `PENDING`, `REJECTED`, `SUPERSEDED`) and the consumption calculated from them. Public API of the module root: `MeterResource`, `MeterKind`, `MeterValue` (a reading: a quantity, not money, at most three decimals, never rounded), `Quantity` (an exact fraction; the one display rounding of a calculated value, `HALF_UP` to three decimals, is `Quantity.toMeterValue`), `ConsumptionCalculator` (pure; intervals, the value at a date with linear interpolation by days marked calculated, range consumption over a chain of meters, the split by parent period, the unusual-consumption check) and the port `ReadingUsage` (asks whether a charge used the readings of a meter in a date range; implemented by the ledger of feature 011). Feature 011 adds the event `PlotMeterPointsChanged` (the points of a plot meter changed; the ledger charges the intervals), the read facades `MeterNetwork` (the points of a meter, the electricity network for a range, stale meters), `MeterCatalog` and `WaterConnections`, and water connections of plots (`plot_water_connection`, V16; tag `water`; `WaterConnectionImport` for the Plots sheet). Money for electricity is calculated from the exact `Quantity` of consumption by feature 011, never from a displayed value. Permissions `METERS_VIEW`, `METERS_MANAGE`, `READINGS_ENTER`, `READINGS_APPROVE`. Since slice 2 of spec 010 the meter register is in code: `GET/POST /meters`, `GET/PUT/DELETE /meters/{id}`, `POST /meters/{id}/remove`, `GET /plots/{id}/meters` (tag `meters`), audited as `METER_*`. Since slice 3 readings and the round are in code (tag `readings`): `GET /readings/round`, `POST /readings` (a batch of up to 100 rows, each in its own transaction, answered per row), `PUT`/`DELETE /readings/{id}` (a correction is a new row superseding the old one), `GET /meters/{id}/readings` (history with intervals), with the ordering and date rules, the unusual-consumption warning and the `ReadingUsage` check, audited as `READING_*` (entity type `METER_READING`). Since slice 4 replacement and the member flow are in code: `POST /meters/{id}/replace` (one action: the old meter removed with its final reading, the new one installed the same day with `replaces_meter_id`, taking over the old meter's parent and, for a meter that is not a plot meter, the meters under it; a counter that wrapped around is a replacement by itself), `GET /meters/{id}/consumption` (range consumption added up over the chain of replacements), `POST /plots/{id}/readings` (a current owner submits a `PENDING` reading that never counts until approved), `GET /readings?status=PENDING`, `POST /readings/{id}/approve` and `/reject` (`READINGS_APPROVE`; approval re-checks the reading and supersedes a counted one of the same date, a rejection carries the reason the member sees); a meter with pending readings is neither removed nor replaced (409 `METER_HAS_PENDING_READINGS`); audited as `METER_REPLACED`, `READING_SUBMITTED`, `READING_APPROVED`, `READING_REJECTED`. Dependencies: `meters` -> `plots` (`PlotCatalog`: facts with the street, `findAndLockShared` before a plot meter is registered, `inStreetOrder`, `findActiveByNumbers`; and it implements the veto SPI `plots.PlotChangeGuard`: a plot with a meter is not deleted or turned into common land, a plot with an active meter is not archived, by hand or by the Excel import, 409 `PLOT_HAS_METER`; ADR 0011), `identity`, `audit` and `problem`; `plots` never depends on `meters`. The tree endpoints and the Excel sheet are in code since slices 5 and 6. The repeatable demo script (`R__demo_data.sql`, block `demo meters`) loads a realistic network for the demo cooperative: a substation, two quarters, a well and street lighting, plot meters in the tree with their parent periods, four water meters, about 170 readings over ~120 days (dates relative to `CURRENT_DATE`), one replacement, one meter without a reading for more than 60 days, pending/rejected/superseded examples, one meter in the other cooperative, and the `METERS_VIEW` and `READINGS_ENTER` permissions for the demo custom role "Електрик". The demo water connections and a meter of plot 25 installed before the electricity tariff belong to the block `demo ledger` (see `ledger`). Details in `backend/src/main/java/app/nadel/meters/README.md`.
  - `ledger` (feature 011; tables `billing_month`, `charge`, `charge_part`, `meter_value_use` in V13, `not_charged_item`
    in V15, `losses_run`, `losses_run_node` in V17, `common_consumer_run`, `common_consumer_run_line`,
    `common_consumer_billing`, `common_consumer_user` in V18, all under RLS `tenant_isolation`; `charge` and
    `charge_part` are append-only by privileges and a trigger, ADR 0006; a closed `billing_month` never changes and no
    month is deleted, trigger `billing_month_guard` in V14; runs change only by their reversal, trigger
    `run_reversal_guard` in V17): the charges of plots (kinds `REGULAR`, `REVERSAL`, `OPENING`; sources `TARIFF`,
    `CONSUMPTION`, `LOSSES`, `COMMON_CONSUMER`, `OPENING`, `MANUAL_REVERSAL`), the billing months they are booked in
    (created on demand, `OPEN` by default; a charge of a closed month goes to the earliest open month after it, pure
    `BillingCalendar`; months close in order with a check list that never blocks), the month run of recurring tariffs
    (preview with a digest, idempotent rerun, conflicts queued), a charge per interval of a plot meter (listener of
    `meters.PlotMeterPointsChanged`, ADR 0007 pattern; fixed-percentage losses), proportional losses runs and common
    consumer runs over a range of days (pure `LossesCalculator`, `CommonConsumerSplitCalculator`; preview, overlap
    refusal, reversal of a whole run), the "not charged" queue with retries, manual reversals, opening balances (by hand
    and by the import sheet, facade `OpeningBalanceImport`) and the debt per charge type (pure `DebtCalculator`). Runs
    are synchronous in the request transaction; nothing runs on a schedule. Public API of the module root: the event
    `ChargesBooked(tenantId, plotIds)`, published after every booking or reversal; its listener `PlotStatusRefresher`
    (ADR 0007 pattern) rebuilds the debt cache of the plots through `plots.PlotDebtCache`, and `plots` derives the
    colour at read time from the cached debt, the red threshold of the settings (`redAmount`, `redDays`, V14) and
    today; and `OpeningBalanceImport`. Endpoints (tags `charges`, `billing`, `losses-runs`, `common-consumer-runs`,
    `not-charged`, `recalculations`): the plot account (`CHARGES_VIEW`, or a current owner; any other plot 404 `PLOT_NOT_FOUND`),
    reversals (`CHARGES_REVERSE`), opening balances, month runs, runs and retries (`CHARGES_RUN`), closing
    (`PERIODS_CLOSE`), the months, runs, queue and recalculations (`CHARGES_VIEW`), recalculating
    (`RECALCULATION_RUN`, feature 012), the split of a common consumer (`SETTINGS_MANAGE`);
    every action audited (entity types `CHARGE`, `BILLING_MONTH`, `LOSSES_RUN`, `COMMON_CONSUMER_RUN`, `METER`).
    Dependencies: `ledger` -> `tariffs` (`ChargeTypeCatalog`, `TariffBook`, `TariffResolver`, `ChargeCalculator`;
    it implements `TariffVersionUsage`), `plots` (`PlotCatalog`, `PlotDebtCache`; it implements `PlotChangeGuard`: a
    plot with charges is never deleted, 409 `PLOT_HAS_CHARGES`), `meters` (`MeterNetwork`, `MeterCatalog`,
    `WaterConnections`, `ConsumptionCalculator`, the event `PlotMeterPointsChanged`; it implements `ReadingUsage`),
    `settings`, `money`, `identity`, `audit` (events, `ActorNames`), `problem`, `tenancy`; `imports` depends on
    `ledger` (the opening balances sheet) and, since feature 013, `reports` reads it through `LedgerReadModel`. The demo cooperative's ledger is the block `demo ledger` of the demo
    script (section 2). Details in `backend/src/main/java/app/nadel/ledger/README.md`.
    **Payments and allocations (feature 015) live in `ledger` too**, because the debt is "charges − allocations" and one
    transaction changes both (ADR 0006). Tables `payment` (kinds `PAYMENT`/`PAYMENT_REVERSAL`, sources `CASH`/`BANK`,
    the payer note sealed as `payer_note_enc` by the `privacy` cipher, ADR 0016), `allocation` (positive rows place money
    on a `REGULAR` or `OPENING` charge, negative rows release it) and `receipt_counter` (one gap-free counter per
    cooperative, taken in the saving transaction) in V27, all under RLS `tenant_isolation`, `payment` and `allocation`
    append-only like `charge`, with guard triggers and deferred constraint triggers as backstops (allocations never
    exceed a payment or a charge's effective amount; a charge reversal or adjustment and a payment reversal must have
    released their allocations by commit). Pure calculators: `OpenAmounts`, `AllocationPlanner` (oldest debt first, then
    the charge type order), `PaymentSplitter` (one payment over several plots of a payer, rounding through
    `Money.allocate`), `ReleasePlanner`, `PaymentDigest`. Concurrency: a per-plot transaction advisory lock
    `PlotLedgerLock` in the lock order charging lock → plot `FOR SHARE` → plot ledger locks → billing month `FOR SHARE` →
    receipt counter. Every path that reverses or lowers a charge (hand reversal, run reversals, recalculation) locks the
    plots and calls `AllocationReleaser`, so the paid money becomes the plot's prepayment again. With the cooperative
    setting `autoAllocatePrepayment` (V27, default on) a payment's unallocated remainder is placed at once on the plot's
    open debts in the same transaction (owner decision 28.10.2026), and `PrepaymentAllocator` (an after-commit listener of
    `ChargesBooked`, ADR 0007 pattern, idempotent) places a remaining prepayment on new charges; with the setting off the
    treasurer allocates prepayment by hand. Public API additions: the event `PaymentsChanged(tenantId, plotIds)` (the
    plot status is refreshed after every payment, allocation and release; `plot_status.prepayment` in V27),
    `PaymentSource`, `PaymentRow`, `PaymentFilter` and the port `PaymentNotes` (the only read of personal data from the
    ledger, gated by `MEMBERS_VIEW`, for the payments report). Endpoints (tag `payments`): entry with a preview and a
    digest (one plot or a split), list, card, reversal (`PAYMENTS_REVERSE`, Chairman), reallocation and manual prepayment
    allocation (`PAYMENTS_ENTER`, Chairman and Treasurer), reading (`PAYMENTS_VIEW`); a member sees the payments of their
    own plots on the plot account. Audited as `PAYMENT_RECORDED`, `PAYMENT_REVERSED`, `PAYMENT_REALLOCATED`,
    `PREPAYMENT_ALLOCATED` (entity type `PAYMENT`); automatic allocations and releases are ledger rows only.
  - `reports` (feature 013, ADR 0015; tables `report_template` and `report_template_version` in V20, under RLS `tenant_isolation`): nine built-in reports (`charges-by-month`, `plot-balances`, `debtors`, `electricity-tree`, `water`, `missing-readings`, `land-tax`, `corrections`, and since feature 015 `payments`) shown as a preview and downloaded as `.xlsx`, and report templates (uploaded `.xlsx` files, versioned, archived, never deleted) filled by an own streaming OOXML filler from a field catalog. No public API, no domain events and no money rule: it only reads, through the read facades `ledger.LedgerReadModel`, `meters.MeterReports`, `plots.PlotCatalog`/`PlotOwners`/`PlotStatuses`, `members.MemberDirectory`, `settings.CooperativeSettings`, `tariffs.ChargeTypeCatalog`/`ExpectedAmounts`, `tenancy.TenantDirectory`, and `xlsx.WorkbookGuard` for uploads; it audits through `audit` (`REPORT_GENERATED`, `REPORT_TEMPLATE_UPLOADED`, `REPORT_TEMPLATE_ARCHIVED`). Permissions `REPORTS_VIEW` (a report with personal data, Debtors, also needs `MEMBERS_VIEW`) and `REPORT_TEMPLATES_MANAGE`. Endpoints (tags `reports`, `report-templates`): `GET /reports`, `GET /reports/{code}`, `GET /reports/{code}.xlsx`, `/report-templates` (list, upload, versions, archive, file, catalog, generate). No module depends on `reports`. Details in `backend/src/main/java/app/nadel/reports/README.md`.
  - Modules: `documents` (in code since feature 016); planned: `notifications` (payments live in `ledger` since feature 015).
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
  - Owner-side Java migrations (feature 014, ADR 0016) encrypt existing data on Flyway's connection with `privacy.OwnerKeyring`: 22.1 (member contacts), 24 (audit values) and 25.1 (the pending Excel import plan). The key-check callback (`KeyCheckCallback`, Flyway `AFTER_MIGRATE`) verifies every cooperative's wrapped keys against `NADEL_PD_MASTER_KEY` on each start and re-wraps them during a master key rotation (`NADEL_PD_MASTER_KEY_PREVIOUS`); Flyway must therefore stay enabled in `prod`.
  - Migrations must be backward-compatible (expand/contract) so the previous release keeps running during a deploy.
  - Demo data is a repeatable script, `db/demo/R__demo_data.sql`, loaded only in the dev profile. Its block `demo ledger`
    (feature 011) books the demo cooperative's ledger again on every run, as `nadel_owner` with the owner-only bypass
    of the ledger triggers, and runs only while the Flyway placeholder `demoledger` is `on` (the dev profile sets it;
    integration tests turn it off and load the block on purpose, `DemoLedgerData`).
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
  - Types: `BigDecimal` through the `Money` value object in Java, `numeric(14,2)` in the database. Rounding is `HALF_UP` to 2 places and happens only in `Money` **[in code, since spec 009]**: `app.nadel.money.Money`, with `Money.ofQuotient` as the end of a calculation (ADR 0013).
  - Tariff rates are `Rate` in Java (never rounded, at most four decimals), `numeric(14,4)` in the database and a string with four decimals in JSON (`"4.3200"`); a rate is not money.
  - The ledger tables are append-only: `charge` and `charge_part` **[in code, `V13__ledger_core.sql`]** (`nadel_app` has
    no `UPDATE`, `DELETE` or `TRUNCATE`, and a trigger refuses them to every role except `nadel_owner` with the session
    setting `app.ledger_maintenance`, used by demo data and test helpers only); a closed `billing_month` and the runs
    (`losses_run`, `common_consumer_run` and their rows) are guarded the same way (V14, V17, V18); `payment` and
    `allocation` **[planned, phase 3]**.
  - Balances are always calculated, never stored.
  - Corrections are a `REVERSAL` plus a new entry, or an `ADJUSTMENT` in the current open period. A closed period never changes.
  - The `plot_status` map-color cache **[in code, `V1__foundation.sql`]** can be rebuilt from the ledger at any time.
- **Documents (invoices, certificates, receipts):** the `documents` module **[in code, feature 016]**. It keeps the data that was printed (invoice snapshots with versions, certificates with a numbered register, a per-cooperative counter), never the PDF: each PDF is rendered on demand with Apache PDFBox and an embedded font (IBM Plex Sans, OFL), so the same version always gives the same document. Documents are always in Ukrainian. See ADR 0017.
- **Object storage (meter photos):** S3-compatible **[to decide, phase 5]**. Invoice PDFs do not need it: they are rendered on demand and not stored.
  - The product gets its own ADR. MinIO stopped publishing community images.
  - Candidates: Garage, SeaweedFS, or managed S3 from the hosting provider.
  - The backend should use only the S3 API, so the product can be swapped later.
- **Event outbox:** the Spring Modulith Event Publication Registry (`spring-modulith-starter-jpa`) **[in code, since spec 002]**. Domain events are stored in the same transaction as the change that raised them and redelivered until their listeners finish; outstanding ones are resubmitted at startup, completed ones are deleted. All cross-module side effects (notifications, recalculations, status cache refresh) go through it. See ADR 0007.
  - The table `event_publication` (`V3__audit_and_event_publication.sql`) is the one table without `tenant_id` and RLS: startup resubmission runs without a tenant. Listeners bind the event's `tenantId` with `TenantContext.runAs` before opening their transaction (ADR 0007 implementation notes).
- **Background jobs:** JobRunr with PostgreSQL storage **[planned, arrives with feature 017, delivery by e-mail and Telegram]**. It runs scheduled and retryable jobs: monthly runs on a schedule, delivery retries to external channels. It is not the event outbox. See ADR 0007. Features 011 and 016 need none (the month's invoices are built while the treasurer waits, in one transaction): month, losses and common consumer runs are started by the board and run synchronously in the request transaction; charges of readings are event listeners.
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
- **Authentication flow** **[in code, since spec 002]** (`identity/internal/MemberAuthenticationConverter`, see the `identity` README):
  1. A token with the realm role `PLATFORM_OPERATOR` becomes the operator, with no database access and no permissions (ADR 0008).
  2. Otherwise the `tenant_id` claim is bound with `TenantContext.runAs` and the active member of that cooperative is looked up: by Keycloak subject; on first sign-in, by email (case-insensitive) only when `email_verified` is true and the member is not yet linked, and the subject is then stored on the member. A member linked to another subject is never relinked; archived members are never found.
  3. No member: 403 `MEMBER_NOT_LINKED`. A failure of the lookup itself is 500, not 401.
  4. The union of the member's role permissions becomes the `PERM_*` authorities. Nothing is cached, so a role change applies on the next request.
- **Authorization:** always on the server, with `@PreAuthorize("hasAuthority('PERM_...')")` **[in code, since spec 002]**. Clients hide UI by permission only for convenience (web: `useCan`, `RequirePermission`). See ADR 0009:
  - A fixed list of permissions is defined in code (`identity.Permission`: `MEMBERS_VIEW`, `MEMBERS_EDIT`, `ROLES_MANAGE`, `AUDIT_VIEW`, `PLOTS_VIEW`, `PLOTS_EDIT`, `OWNERSHIP_EDIT`, `IMPORT_RUN`, `SETTINGS_MANAGE`, `TARIFFS_VIEW`, `TARIFFS_MANAGE`, `METERS_VIEW`, `METERS_MANAGE`, `READINGS_ENTER`, `READINGS_APPROVE`, `CHARGES_VIEW`, `CHARGES_RUN`, `CHARGES_REVERSE`, `PERIODS_CLOSE`, `RECALCULATION_RUN`; later features add theirs, such as `PAYMENTS_ALLOCATE`). The Treasurer base role has `METERS_VIEW` and `CHARGES_VIEW`; the Chairman has all twenty.
  - Roles are named permission sets stored per cooperative (`role`, `role_permission`, `member_role`, tenant-scoped with RLS). Base roles `CHAIRMAN`, `TREASURER`, `MEMBER` are seeded by `seed_base_roles` and cannot be renamed or deleted; Chairman always has every permission; at least one active chairman must remain. Custom roles (for example "Secretary", or "Electrician" for meters) are created by anyone with `ROLES_MANAGE`.
  - Code checks permissions, never role names. A caller without `MEMBERS_VIEW` gets 404 for any member card but their own, so other members' existence is not revealed.
- **Data visibility for members:** a member sees debt amounts, owners, status and personal data only for plots they own. The status color of other plots is visible only when the chairman sets the cooperative setting `memberPlotStatusVisibility` to `ALL_PLOTS` (default `OWN_ONLY`, feature 007). The API enforces this (`GET /plots` and `GET /plots/{id}` omit `status` on plots the caller may not see and omit owners, area and personal data on plots the caller does not own; `GET /me` returns `ownedPlots`), not just the UI **[in code, since spec 007]**.
- **Platform operator:** sees no cooperative rows (no `tenant_id`, so RLS returns nothing). Platform reports use aggregate views without personal or financial detail through a separate read-only role; support access is granted by the chairman for a limited time and audited. See ADR 0008.
- **Audit:** every admin action that changes data is written to the audit log **[in code, `audit` module]**. The changing module publishes an `AuditEvent` in its transaction; a listener writes `audit_entry` through the outbox (idempotent by event id). `nadel_app` has only `SELECT` and `INSERT` on `audit_entry`. Read with `GET /audit` (`AUDIT_VIEW`). So far members, roles, role assignments, plots, ownership, imports, cooperative settings, charge types, tariffs, tariff versions, tariff scopes, meters, readings, charge reversals and opening balances are audited.
- **Logging hygiene:** names, phone numbers, emails and addresses never appear in logs.
- **Secrets:** only from environment variables. The repository contains only dev values for the local docker-compose (`.env.example`, gitleaks allowlist in `.gitleaks.toml`).

---

## 4. External Services & APIs

- **Email:** a transactional provider **[to decide: Postmark or Amazon SES]**. Locally, a test mail server is planned for phase 4 (comment in `infra/docker-compose.yml`).
- **Messaging:** Telegram Bot API and Viber Bot API **[planned, phase 4]**. Viber charges for business messages, so its terms must be checked before committing.
- **Push notifications:** Expo Push Service, which delivers to APNs and FCM **[planned, phase 5]**.
- **Delivery pattern:** every channel sits behind the `notifications` module. Other modules trigger delivery through domain events and the outbox, never through direct calls.
- **Payments, now:** the NBU payment QR code (Resolution No. 97, format version 003) printed on invoices, prefilled with recipient, IBAN, ЄДРПОУ, amount and a purpose with the plot number; the payer may only change the amount **[in code, feature 016]**. The image is generated with ZXing (`core`); the layout is in `docs/domain/nbu-qr.md`.
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
  - Browser E2E **[in code, since spec 002]**: Playwright (chromium only) in `web/e2e/`, run against `make infra` + backend + web with `make e2e` locally (browser installed once with `make e2e-install`) and by the CI `e2e` job. Not part of `make check`.

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
7. **Base roles:** Chairman, Treasurer and Member. Electrician is no longer a base role (spec 002); an electrician gets a custom role with the meter permissions (`METERS_VIEW`, `READINGS_ENTER`; feature 010). `CLAUDE.md` still lists `ELECTRICIAN` and says the demo role has no permissions until meters arrive; the owner updates it (the demo role has the two permissions since the demo data of spec 010).

Open:

8. **Nothing exists for delivery yet.** Dockerfiles, a Caddyfile, a deploy workflow, `eas.json`, release-please and OpenTelemetry/Sentry dependencies are all missing. Everything in sections 5 and 6 marked [planned] is unimplemented.
9. **ADRs still to write:** S3-compatible storage product, email provider, Keycloak Organizations, basemap tile source, backup tooling, data residency.
