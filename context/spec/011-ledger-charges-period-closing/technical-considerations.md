# Technical Specification: Ledger, charges and period closing

- **Functional Specification:** [functional-spec.md](functional-spec.md)
- **Status:** Draft
- **Author(s):** Alex Usov (drafted by `/feature-pipeline` with spring-backend, postgres-database and react-frontend input)

---

## 1. High-Level Technical Approach

A new Spring Modulith module **`app.nadel.ledger`** owns charges, billing months, runs, the not-charged queue and
opening balances. It depends on `tariffs`, `meters`, `plots`, `settings`, `money`, `identity`, `audit`, `problem` and
`tenancy`; nothing depends on it except `imports` (the opening-balance sheet). Other modules expose small public
facades and ports; `ledger` implements the existing ports `tariffs.TariffVersionUsage` and `meters.ReadingUsage`.

- **Water connections live in `meters`** (the water source is a common consumer meter, the 010 missing-readings list
  must skip unconnected plots, `imports` already depends on `meters`).
- **Reading charges are event-driven:** `meters` publishes a public event when a plot meter's points change; `ledger`
  listens through the Spring Modulith outbox (ADR 0007 pattern: `@Async @TransactionalEventListener`, `TenantContext.runAs`,
  then a `TransactionTemplate`) and charges idempotently.
- **Runs are synchronous** in the request transaction (month run, losses, common consumers). No JobRunr; scheduled runs
  are out of scope.
- **All money maths is in pure calculators** (no database) so every row of the feature doc §3 is a unit test.
- **Append-only** `charge` and `charge_part`: `REVOKE UPDATE, DELETE, TRUNCATE` from `nadel_app` plus a
  `BEFORE UPDATE OR DELETE` row trigger and a `BEFORE TRUNCATE` statement trigger (ADR 0006).
- **Plot status:** `plots` exposes a debt-cache writer; `ledger` publishes `ChargesBooked` after every booking or reversal
  and a listener recomputes debt and the oldest unpaid accrual date; the status colour is derived from the cached debt,
  the thresholds and today at read time.
- One Flyway migration per slice (V13 onwards), expand-only.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Database (V13+; one migration per slice)

Conventions for every new table: `tenant_id uuid not null`, RLS enabled with the `tenant_isolation` policy (USING and
WITH CHECK on `current_tenant_id()`), `<table>_tenant_idx`, composite FKs `(tenant_id, x_id) → x(tenant_id, id)` (add
`UNIQUE (tenant_id, id)` where a target lacks it), isolation test per table.

| Table | Key columns | Constraints / indexes |
|---|---|---|
| `billing_month` | `month date` (first day), `status` OPEN/CLOSED, `run_at`, `closed_at`, `closed_by` | UNIQUE (tenant_id, month); CHECK first day; CHECK status ⇔ closed_at; trigger refuses CLOSED→OPEN and DELETE |
| `charge` | `plot_id`, `charge_type_id`, `billing_month_id`, `kind` REGULAR/REVERSAL/OPENING, `source` TARIFF/CONSUMPTION/LOSSES/COMMON_CONSUMER/OPENING/MANUAL_REVERSAL, `amount numeric(14,2)`, `service_from`, `service_to`, `accrued_on`, `calculation jsonb` (codes and numbers only, no display text), `source_key text`, `tariff_id`, `meter_id`, `run_id`, `reverses_charge_id`, `reason`, `created_by`, `created_at` | append-only (REVOKE + trigger); CHECK REVERSAL ⇔ reverses_charge_id and reason not null and amount < 0; other kinds amount > 0; UNIQUE (tenant_id, reverses_charge_id) WHERE not null (reversed at most once); UNIQUE (tenant_id, source_key) WHERE kind <> 'REVERSAL' (idempotency); indexes (tenant_id, plot_id, charge_type_id, accrued_on) INCLUDE (amount), (tenant_id, billing_month_id, charge_type_id), (tenant_id, meter_id, service_from, service_to), (tenant_id, run_id) |
| `charge_part` | `charge_id`, `tariff_version_id`, `days int`, `units_num`, `units_den` (exact Quantity), `rate numeric(14,4)`, `node_meter_id` (losses) | append-only (REVOKE + trigger); index (tenant_id, tariff_version_id) for `TariffVersionUsage` |
| `meter_value_use` | `meter_id`, `used_on date`, `charge_id` or `run_id` | one row per point date a charge or run read; index (tenant_id, meter_id, used_on); rows of a reversed charge/run are deleted on reversal (not a ledger table) |
| `not_charged_item` | `item_key`, `kind` TARIFF_MONTH/INTERVAL/LOSSES_NODE/COMMON_CONSUMER, `plot_id` null, `charge_type_id` null, `meter_id` null, `month`, `from`, `to`, `reason` NO_TARIFF_FOR_ALL_DAYS/CONFLICTING_TARIFFS/OUTSIDE_SCOPE/EXCLUDED/UNKNOWN_NODE_VALUE/UNKNOWN_CONSUMER_VALUE, `params jsonb`, `status` OPEN/RESOLVED/OBSOLETE, `resolved_at` | UNIQUE (tenant_id, item_key) WHERE status = 'OPEN'; index (tenant_id, status) |
| `losses_run` | `from_date`, `to_date`, `status` BOOKED/REVERSED, author, `reversed_at/by`, `reverse_reason` | |
| `losses_run_node` | `run_id`, `node_meter_id`, `run_status` (kept in step), consumption, children, losses, pct, outcome, `calculated` | `EXCLUDE USING gist (tenant_id =, node_meter_id =, daterange(from,to,'[]') &&) WHERE (run_status = 'BOOKED' AND outcome = 'CHARGED')` → 409 `LOSSES_RANGE_OVERLAP` |
| `common_consumer_run` | `consumer_meter_id`, `from_date`, `to_date`, `split`, `status`, reversal columns, `outcome` | same partial EXCLUDE per consumer → 409 `COMMON_CONSUMER_RANGE_OVERLAP` |
| `common_consumer_run_line` | `run_id`, `plot_id`, `weight` (plot-days), units, `charge_id` | UNIQUE (run_id, plot_id) |
| `common_consumer_billing` | `consumer_meter_id`, `split` | UNIQUE (tenant_id, consumer_meter_id); missing row = cooperative default |
| `common_consumer_user` | `consumer_meter_id`, `plot_id` | PK (tenant_id, consumer_meter_id, plot_id) |
| `plot_water_connection` (meters) | `plot_id`, `source_meter_id` null = "Водопровід кооперативу", `valid_from`, `valid_to` null | `EXCLUDE USING gist (tenant_id =, plot_id =, daterange(valid_from, valid_to, '[]') &&)`; partial UNIQUE (tenant_id, plot_id) WHERE valid_to IS NULL |
| `cooperative_settings` (+cols) | `losses_mode` default FIXED_PERCENT, `losses_percent numeric(5,2)` default 0, `common_consumer_default_split` default CONNECTED_PLOTS, `red_amount numeric(14,2)` default 1000.00, `red_days int` default 90 | CHECKs on values and ranges |

- **Permissions** `CHARGES_VIEW` (Chairman, Treasurer), `CHARGES_RUN`, `CHARGES_REVERSE`, `PERIODS_CLOSE` (Chairman):
  `CREATE OR REPLACE seed_base_roles` with the full matrix (still ending with `seed_charge_types`) plus targeted inserts
  for existing cooperatives (V10/V12 pattern). `Permission` enum, `BaseRoles`, `PermissionCatalog`, contract enum,
  catalogs.
- **`COMMON_CONSUMERS` charge type:** `CREATE OR REPLACE seed_charge_types` adds it (position 5, active, deterministic
  id), then `SELECT seed_charge_types(id) FROM tenant`. Default names in
  `charge-type-default-names.properties`, `DefaultChargeTypeNames`, `web/src/chargeTypeName.ts`, catalogs.
- **Trigger and demo/test maintenance:** the trigger function allows a row change only when `current_user =
  'nadel_owner'` and `current_setting('app.ledger_maintenance', true) = 'on'` (set with `SET LOCAL`), so the demo block
  and test helpers (running as the owner) can clear demo charges; `nadel_app` is refused even with the setting
  (tested).

### 2.2 Backend

**Public facades in other modules**

- `money.Money`: `negate()`, `signum()`, `minus()`.
- `tariffs`:
  - `ChargeCalculator.breakdown(versions, areaM2, YearMonth, DaySet applicable)` → `Breakdown(Money amount,
    List<Part(versionValidFrom, days, units)>)`; `calculate(...)` = `breakdown(..., DaySet.ALL)` so 009 behaviour and tests
    stay. Days per version = |validity ∩ month ∩ applicable|; `FIXED_ONE_TIME` counts only if its due date is applicable.
  - `DaySet` (pure, public in `tariffs`): sorted disjoint closed date ranges; `ALL`, `of`, `count`, `intersect`.
  - `TariffBook` facade: `snapshot()` (charge types with code/state, tariffs with scope, exclusions and versions as
    `VersionRef(id, VersionTerms)`), `lockShared(tariffIds)` (`FOR SHARE` in id order).
  - `TariffResolver` (pure): `resolve(snapshot, chargeTypeId, PlotFacts, from, to)` → `Single | None(reason) |
    Conflict(tariffs)`. A conflict = two in-scope tariffs of the type with versions valid on a common day of the range.
    The tariffs page uses the same resolver for its conflict warning.
- `meters`:
  - Event `PlotMeterPointsChanged(tenantId, meterId, plotId, resource, LocalDate on, cause)` published by
    `ReadingCommands` (enter/approve/correct/delete, import) and `MeterCommands` (remove, replace) for `PLOT` meters only.
  - `MeterNetwork` facade: `lockSharedPoints(meterId)` (meter `FOR SHARE` + its points with stable keys:
    `INSTALL:{meter}`, the reading id, `REMOVAL:{meter}`); `network(from, to)` (electricity meters, chains, parent
    periods, for losses and common consumers); `staleMeters(asOf, days)` (closing check list).
  - `WaterConnections` facade: `connectedDays(plotIds, from, to)` → `Map<plotId, DaySet>`, `connectedToSource(meterId,
    from, to)`, plus `WaterConnectionImport` for the Plots sheet column. Connect/disconnect/bulk commands, audit
    `WATER_CONNECTED` / `WATER_DISCONNECTED`. The 010 water missing-readings list skips unconnected plots.
  - **ReadingUsage span fix:** `ReadingCommands`/`ReadingQueries` ask the port about the affected days `[prev + 1, next
    − 1]` (or the reading's own date) instead of `[prev, next]`, so a new reading after a charged one is not refused.
- `plots`: `PlotDebtCache` facade — `lockAll(plotIds)` (`FOR UPDATE` in id order) and `record(plotId, Money debt,
  LocalDate oldestDebtDate)`. `PlotStatusPolicy.statusOf(kind, hasOwner, debt, oldestDebtDate, thresholds, today)` is pure;
  the status is derived at read time so RED by age appears without a nightly job. `plots` reads thresholds through
  `settings.CooperativeSettings` (already a dependency).
- `settings.CooperativeSettings`: `lossesMode()`, `lossesPercent()`, `commonConsumerDefaultSplit()`, `redAmount()`,
  `redDays()`; `settings` gains a dependency on `money`.

**`ledger` module**

- Public root: `ChargesBooked(tenantId, Set<UUID> plotIds)` event; `OpeningBalanceImport` (plan/lock/apply like
  `meters.MeterImport`).
- `ledger.internal` services: `BillingCalendar`, `MonthRun`, `ReadingCharges` (listener), `LossesRuns`,
  `CommonConsumerRuns`, `Reversals`, `OpeningBalances`, `NotChargedQueue`, `PlotStatusRefresher` (listener),
  `LedgerTariffVersionUsage`, `LedgerReadingUsage`, `LedgerPlotGuard` (implements `plots.PlotChangeGuard`: a plot with
  charges cannot be deleted, 409 `PLOT_HAS_CHARGES`), `LedgerAuditPublisher`; controllers per resource implementing the
  generated interfaces.
- **Pure calculators** (unit tests = feature doc §3 rows):
  - `ReadingChargeCalculator.price(prevPoint, readingOn, Quantity, versions, DaySet applicable, lossesPct?)` →
    `Priced(amount, parts, losses?) | NotCovered(gaps) | NotApplicable`. Days = prevPoint+1 … readingOn inclusive;
    kWh split by days per version; `Money.ofQuotient(Σ rate·days·num, N·den)` rounded once; fixed-% losses is a second
    amount over the same parts, rounded once. Water: the days not connected are dropped by days (`NotApplicable` when
    none is connected).
  - `LossesCalculator.calculate(List<NodeInput>)` → per node `Charged | Negative | Unknown | SkippedAbove |
    NotSelected` and per plot `List<NodeShare(node, Quantity)>`; bottom-up; children from tree slices; shares in
    proportion to each plot's consumption under the node; a plot's kWh across nodes priced once over the range.
  - `CommonConsumerSplitCalculator.split(Quantity, mode, from, to, Map<plot, DaySet> eligible)` → plot-days for
    `CONNECTED_PLOTS`, equal for `EQUAL_PER_PLOT` and `SERVICE_USERS`, empty for `NOT_CHARGED`.
  - `DebtCalculator.of(lines)` → debt per charge type, total, oldest unpaid accrual (until payments: the oldest accrual
    date among non-reversed positive charges).
  - `BillingCalendar.bookingMonth(date, months, today)`: the date's month if open (not after the current month); else
    the earliest open month after it; else a new month after the last closed one.
- **Range semantics for runs:** a run over `[from, to]` (days inclusive) uses `valueAt(to) − valueAt(from − 1)` per
  meter (end-of-day values), consistent with the reading-interval days rule, so back-to-back runs neither overlap nor
  leave a gap. Documented in money-rules 6.4. The 010 tree screen keeps its own definition.
- **Reading charges:** the listener locks the meter points, recomputes the interval ending at `on` and the one starting
  at `on`, and charges each interval not yet charged (`source_key = CONSUMPTION:{fromKey}:{toKey}`, insert with ON
  CONFLICT DO NOTHING). Business failures never throw; they write a queue item (`INTERVAL:{meter}:{toKey}`). A reversed
  interval charge is not re-created automatically; a correction (new reading) produces new keys.
- **Month run:** `pg_advisory_xact_lock(tenant, month)`, tariffs `FOR SHARE`, month `FOR SHARE`; per plot × charge type
  resolve → `ChargeCalculator.breakdown` with the water mask for `WATER` types; key `TARIFF:{tariff}:{plot}:{YYYY-MM}`.
  A reversed tariff charge is not re-created by a rerun (correction belongs to 012). A preview runs the same code
  without writing and returns a digest; booking with a stale digest → 409 `PREVIEW_OUTDATED`. The run also retries
  open `TARIFF_MONTH` and `INTERVAL` items.
- **Lock order:** meter → tariff → billing_month; closing takes the month `FOR UPDATE` and checks earlier months.
  Reversal takes the original charge's month `FOR SHARE`. Status refresh runs in its own later transaction.
- **Usage ports:** `TariffVersionUsage.isUsed` = a non-reversed charge has a part with that version.
  `ReadingUsage.isUsed(meter, from, to)` = a `meter_value_use` row of that meter in `[from, to]` whose charge or run is
  not reversed.
- **Plot status:** every booking or reversal publishes `ChargesBooked` (≤ 500 plots per event). `PlotStatusRefresher`
  locks the plots' cache rows, sums the committed ledger with `DebtCalculator`, records debt and oldest date.
- **Problem codes:** `CHARGE_ALREADY_REVERSED`, `REVERSAL_NOT_REVERSIBLE`, `CHARGE_IN_CLOSED_MONTH`,
  `PERIOD_CLOSE_ORDER`, `PERIOD_CLOSED`, `PERIOD_IN_FUTURE`, `LOSSES_RANGE_OVERLAP`, `COMMON_CONSUMER_RANGE_OVERLAP`,
  `NO_COMMON_CONSUMERS_TARIFF`, `LOSSES_MODE_NOT_PROPORTIONAL`, `RUN_ALREADY_REVERSED`, `RUN_IN_CLOSED_MONTH`,
  `PREVIEW_OUTDATED`, `WATER_ALREADY_CONNECTED`, `WATER_NOT_CONNECTED`, `WATER_SOURCE_INVALID`, `PLOT_HAS_CHARGES`;
  field codes `AMOUNT_NOT_POSITIVE`, `DATE_IN_FUTURE`, `REQUIRED`. Exclusion violations (23P01) and unique violations
  map to the 409 codes, never a raw SQL error.
- **Audit actions:** `BILLING_MONTH_RUN`, `BILLING_MONTH_CLOSED`, `CHARGE_REVERSED`, `OPENING_BALANCE_ENTERED`,
  `LOSSES_RUN_BOOKED` / `_REVERSED`, `COMMON_CONSUMER_RUN_BOOKED` / `_REVERSED`, `NOT_CHARGED_RETRIED`,
  `WATER_CONNECTED` / `_DISCONNECTED`, `COMMON_CONSUMER_BILLING_CHANGED`, existing `SETTINGS_UPDATED`; import via
  `AuditBatch`. Charges themselves are not duplicated in the audit log.
- **Authorization:** `@PreAuthorize` per endpoint with the four permissions; the account is open to `CHARGES_VIEW` or
  a current owner (else 404 `PLOT_NOT_FOUND`, `mayAsk` pattern); water connections viewed with `PLOTS_VIEW` or by the
  owner, changed with `METERS_MANAGE`; common consumer billing settings and losses/threshold settings with
  `SETTINGS_MANAGE`.

### 2.3 API contract (`api/openapi.yaml`, tags `billing`, `charges`, `losses-runs`, `common-consumer-runs`, `not-charged`, `water`)

- `GET /billing-months` → `[{month, status, runDone, totals[{chargeTypeId, name, nameCustomized, amount}], total}]`;
  `GET /billing-months/{month}` adds `checklist{runDone, notCharged[], staleMeters[{plotId, number, lastReadingOn}]}`;
  `POST /billing-months/{month}/run?preview=true|false` body `{digest?}` → `{plots, totals[], notCharged[], created,
  digest}`; `POST /billing-months/{month}/close`.
- `GET /plots/{id}/account?from&to` → `{debts[{chargeTypeId, name, nameCustomized, debt}], total, charges[{id, kind,
  source, chargeType, amount, billingMonth, serviceFrom, serviceTo, accruedOn, reason, reversedBy, reverses, reversible,
  calculation{parts[{quantity, unit, rate, days, from, to, calculated}], amount, readings[], node?, consumer?}}]}`.
- `POST /charges/{id}/reverse {reason}`; `POST /plots/{id}/opening-balances {chargeTypeId, amount, date, reason}`.
- `POST /losses-runs?preview= {from, to, nodeIds?, digest?}` → `{nodes[{meterId, name, consumption, children,
  losses, pct, calculated, outcome, warning{code, params}, plots[{plotId, number, kwh, amount}]}], total, digest}`;
  `GET /losses-runs`, `GET /losses-runs/{id}`, `POST /losses-runs/{id}/reverse {reason}`. Common consumer runs: the same
  shape per consumer (`split`, `lines[]`, `noTariff`).
- `GET /not-charged`; `POST /not-charged/{id}/retry` → `{resolved, item?}`.
- `GET|POST /plots/{id}/water-connections {sourceMeterId?, from}`, `POST /plots/{id}/water-connections/{cid}/end
  {until}`, `GET /water-sources` (wells and the cooperative supply), `POST /water-sources/{sourceId}/connections
  {plotIds, from}` and `.../disconnections {plotIds, until}` (`sourceId` = meter id or `cooperative`).
- Settings: `CooperativeSettings` schema gains `lossesMode`, `lossesPercent`, `commonConsumerDefaultSplit`,
  `redAmount`, `redDays`; `GET|PUT /common-consumers/{meterId}/billing {split, servicePlotIds}`.
- Amounts are `Money` strings, kWh `MeterValue` strings, rates `Rate` strings; enums and problem codes only, no display
  text. `Permission` enum gains the four values.

### 2.4 Import (extends 008)

- New `ImportSheet.OPENING_BALANCES` ("Вхідне сальдо" / "Opening balances"): plot number, charge type (customised or
  default name in either language), amount, date, optional reason (empty → the import run is the reference); key
  `OPENING:{importId}:{row}`; preview, validation, both languages; labels in `template_{uk,en}.properties`,
  `header-aliases.properties`, `imports/README.md`.
- `PlotColumn.WATER` in the Plots sheet: так/yes → the cooperative's only water source, a source name → that source,
  ні/no/empty → unchanged; applied after the plots, connected from the import date.

### 2.5 Web

- Routes (lazy, `RequirePermission`): `/charges` (months), `/charges/months/:ym`, `/charges/losses`,
  `/charges/common`, `/charges/not-charged`; one menu item "Нарахування" (`CHARGES_VIEW`) with a `ChargesTabs` bar
  (Місяці, Втрати, Спільні споживачі, Не нараховано). Action buttons gated by their own permission via `useCan(perm)`.
- Pages and components (`web/src/pages/charges/`, `web/src/components/charges/`): `ChargesPage`,
  `ChargesMonthPage` (`MonthChecklist`, `RunPreviewDialog`, `CloseMonthDialog`), `RunRangePage` shared by Losses and
  Common consumers (range form, preview table, `RunWarnings`, "розрахунковий" badge, `RunHistory`,
  `ReverseRunDialog`), `NotChargedPage` ("Повторити"), `PlotAccountPanel` (`DebtByType`, `ChargeList`,
  `ChargeDetailDialog`, `ReverseChargeDialog`, `OpeningBalanceForm`) on the plot card and read-only in "Мій кабінет →
  Мої нарахування", `WaterConnectionPanel` on the plot card; settings sections `LossesSettingsSection`,
  `CommonConsumersSection`, `WaterSourcesSection` (bulk connect/disconnect), `StatusThresholdSection`.
- The existing `MyChargesPanel` (expected amounts) is renamed `MyExpectedChargesPanel` with the title "Очікувані
  нарахування"; "Мої нарахування" is the ledger.
- Generated hooks only; invalidation wrappers (`useChargesInvalidation`) refresh months, month page, account, queue,
  map and plots. Previews are never cached. Reading charges are asynchronous: the account refetches after an approval.
- i18n: `charges.*` keys; enums `charges.kind.*`, `charges.source.*`, `charges.reason.*`, `charges.outcome.*`; problem
  codes in the problem catalog; "за {period}, нараховано в {month}" as one parametrised key; calculation lines composed
  from server strings via shared formatters (no client arithmetic, no `number` for money). Reversal reasons are user
  content (`data-user-content`).
- Phone width: tables as cards below `sm`, full-height dialogs, horizontally scrolling tabs, 44 px buttons; new pages
  added to `responsive.spec.ts` and `language-purity.spec.ts`.

### 2.6 Demo data and docs

- New `BEGIN/END demo ledger` block in `R__demo_data.sql` (runs as `nadel_owner`, `SET LOCAL
  app.ledger_maintenance = 'on'` to clear and re-insert the demo tenant's ledger rows): water connections, settings
  (PROPORTIONAL losses, thresholds), `COMMON_CONSUMERS` tariff, September (closed) and October (open) months, the
  October run charges, reading charges, one losses run with a warning node, one common consumers run, one reversal,
  one opening balance, one queue item, plot_status debts. A test helper `DemoLedgerData` (clear/restore like
  `DemoMeterData`) and `DemoLedgerDataTests` check that every demo charge equals what the calculators produce from the
  demo inputs (so SQL and code cannot drift).
- Docs: money-rules §2, §3 (kinds, sources, trigger), new "Billing months and closing", §4.1 (ledger calls the
  calculator, water mask), §6 (per-reading charge, days rule, losses modes and runs, negative and unknown losses; the
  old "missing readings when closed" sentence replaced), §6.3 (common consumers as a separate charge type and runs,
  `CONNECTED_PLOTS`), §6.4 (run range semantics), new "Water connection", "Opening balances", §9 (thresholds, YELLOW =
  any other debt); glossary; `context/product/architecture.md` (ledger module now built, JobRunr still planned);
  module READMEs; ADR 0006 implementation note; guides `docs/guide/{uk,en}/board-guide.html` chapters 17–19
  "planned" → "works", owner manual (permissions), screenshot list and PDFs.

---

## 3. Impact and Risk Analysis

- **System dependencies:** `tariffs` (calculator extension, facades, conflict warning), `meters` (event, span fix,
  network facade, water connections, missing-readings list), `plots` (debt cache writer, status policy, delete guard),
  `settings` (columns), `imports` (sheet, column), `members`/`identity` (permissions), web map/legend, demo data.
- **Risks and mitigations:**
  - *ReadingUsage span mismatch* would block every new reading after a charged one → the span fix ships in the same
    slice as the port implementation, with a test entering a reading after a charged interval.
  - *Asynchronous reading charges* (UI and e2e must wait; a crashed listener) → the outbox redelivers on restart
    (ADR 0007); business failures become queue items instead of exceptions; e2e polls the account.
  - *Idempotency and concurrency* → unique `source_key`, partial unique on reversals, exclusion constraints on runs and
    water connections, advisory lock for the month run, fixed lock order; 23P01/23505 mapped to 409.
  - *Demo SQL drifting from calculators* → `DemoLedgerDataTests` recomputes them.
  - *Trigger maintenance bypass* could weaken append-only → owner-only plus `SET LOCAL`; a test proves `nadel_app` is
    refused with the setting on.
  - *Back-dated tariff versions or scope changes* after charges silently disagree with existing charges → out of scope
    (012); noted in money-rules.
  - *Size* (largest feature so far) → ten slices, each with its own migration and tests; pure calculators carry most
    coverage; the floor is raised to the measured level at the end.
  - *Unresolvable queue items* (excluded / out of scope plots for electricity) grow → they stay open by spec; the queue
    lists them per plot once (unique key).

---

## 4. Testing Strategy

- **Unit (no DB):** every §3 row as a test of `ReadingChargeCalculator`, `LossesCalculator`,
  `CommonConsumerSplitCalculator`, `ChargeCalculator.breakdown` (water mask, 12.90), `DebtCalculator`,
  `PlotStatusPolicy`, `BillingCalendar`, `TariffResolver` (conflicts), `DaySet`, `Money.negate/minus`.
- **Integration (Testcontainers):** trigger refusal (UPDATE/DELETE/TRUNCATE as owner and app; maintenance bypass only
  for the owner), reversal rules, month run preview/book/rerun/stale digest/conflict queue, close order and late
  booking, reading approval → charge (entry, member approval, import, replacement, removal), water inactive, fixed %
  losses, losses runs (warning, unknown, overlap, reversal), common consumer runs (modes, no tariff, overlap,
  reversal), queue retry, water connections (overlap, bulk, audit, missing readings), opening balances by hand and by
  sheet uk/en, usage ports (409 then accepted after reversal), plot status refresh and thresholds, permissions allowed
  and denied per endpoint, member visibility (own account only, 404 otherwise), isolation per new table, audit per
  action, `ModularityTests`, seeds (permissions, `COMMON_CONSUMERS` for existing and new cooperatives), demo data.
- **Web unit (Vitest):** gating per permission, preview dialogs, warnings and "розрахунковий", calculation line from
  strings, "за … нараховано в …", problem-code mapping, catalog parity, the renamed expected panel.
- **E2E (Playwright, own database `nadel_e2e`):** chairman runs October (@smoke), approves a member reading and sees the
  electricity charge (@smoke), runs losses with a warning node, closes September (@smoke), reverses a charge; treasurer
  sees no actions; member sees only their own account (@smoke); language purity and responsive cover the new pages.
- `make check` green with the coverage floor raised to the new measured level.

---

## Assumptions (pipeline)

- Water connections belong to `meters`, not `ledger`.
- One migration per slice (V13 onwards) rather than one large migration.
- Reading charges are event-driven and asynchronous; runs are synchronous; no JobRunr.
- A reversed tariff or interval charge is not re-created automatically (rerun or listener); its correction belongs to
  012.
- Runs use end-of-day values (`valueAt(to) − valueAt(from − 1)`).
- The plot status colour is derived at read time from the cached debt and oldest date, so threshold changes and debt
  age apply without a nightly job.
- ReadingUsage is answered from `meter_value_use` rows written with each charge and run.
- The demo ledger is SQL (like the other demo blocks), guarded by a calculator-equality test; the trigger allows the
  owner role to clear ledger rows only with a session setting.
- Water connections are changed with `METERS_MANAGE`; common consumer billing, losses mode and thresholds with
  `SETTINGS_MANAGE`.
- Preview and booking share code; booking with a stale preview digest is refused with `PREVIEW_OUTDATED`.
