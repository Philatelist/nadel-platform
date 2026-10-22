# ledger

The ledger of the cooperative (feature 011): the **charges** of plots, the **billing months** they are booked in,
**reversals**, **opening balances**, the **account** of a plot with the debt per charge type, the **month run** of
recurring tariffs and the **"not charged" queue**. Slices 1 to 3 of spec 011 are in code (slice 2: the billing months
with their totals and check list, closing in order, and the plot status refresh; slice 3: the month run and the
queue); charges from readings, losses and common consumer runs and water connections follow in the next slices.

Tables (V13, all under RLS `tenant_isolation`, an index leading with `tenant_id`, composite foreign keys on
`(tenant_id, id)`):

| Table | What it holds |
|---|---|
| `billing_month` | An accounting period: `month` (its first day), `status` `OPEN` or `CLOSED`, `run_at`, `closed_at`, `closed_by`. Created on demand, `OPEN` by default; never deleted (`DELETE`/`TRUNCATE` revoked). The V14 trigger `billing_month_guard` refuses any change of a closed month (so no reopening), a change of `month`, and every `DELETE`/`TRUNCATE`, to everyone but `nadel_owner` with the maintenance setting |
| `charge` | The ledger. `kind` `REGULAR`/`REVERSAL`/`OPENING`, `source` `TARIFF`/`CONSUMPTION`/`LOSSES`/`COMMON_CONSUMER`/`OPENING`/`MANUAL_REVERSAL`, `amount numeric(14,2)`, service period `service_from..service_to`, `accrued_on` (the age of the debt), `billing_month_id`, `calculation jsonb` (codes and numbers only), `source_key` (idempotency of later runs), `reverses_charge_id` (only on a reversal), `reason` (required on a reversal, optional on other kinds, for example an opening balance), `created_by`, `created_at` |
| `charge_part` | The tariff versions behind a charge (days, an exact quantity `units_num/units_den`, the rate); written by the month run (one row per version) |
| `meter_value_use` | The meter values a charge or run read; written from slice 4 |
| `not_charged_item` (V15) | The "not charged" queue: `item_key`, `kind` `TARIFF_MONTH`/`INTERVAL`/`LOSSES_NODE`/`COMMON_CONSUMER`, `plot_id`, `charge_type_id`, `meter_id`, `month`, `from_date`, `to_date`, `reason`, `params jsonb` (codes and ids only, `tariffIds`), `status` `OPEN`/`RESOLVED`/`OBSOLETE`, `resolved_at`. Not a ledger table: the application updates the status and, while open, the reason and params; `DELETE`/`TRUNCATE` revoked. One open item per key (`not_charged_item_open_key_uq`); an item goes with its plot or meter (`ON DELETE CASCADE`) |

**Append-only (ADR 0006).** `nadel_app` has no `UPDATE`, `DELETE` or `TRUNCATE` on `charge` and `charge_part`, and a
`BEFORE UPDATE OR DELETE` row trigger plus a `BEFORE TRUNCATE` trigger refuse them to everyone else too. The only way
past the trigger is `nadel_owner` with `SET LOCAL app.ledger_maintenance = 'on'`, for demo data and test helpers;
`nadel_app` is refused even with the setting. In Java the entity `ChargeRecord` is `@Immutable` and only ever persisted.
CHECK constraints keep a reversal negative with a reason and a pointer, every other row positive (a reason optional);
the partial unique
index `charge_reversed_once_uq` lets a charge be reversed once.

## Public API

| Type | Use |
|---|---|
| `ChargesBooked(tenantId, plotIds)` | Published inside the transaction of every booking or reversal (a reversal, an opening balance, the month run and a retry, at most 500 plots per event; every later booking path must publish it too). `PlotStatusRefresher` recomputes the debt of those plots from the committed ledger. Ids only, no personal data; the set is never empty |

Everything else is in `internal`.

## Rules (pure, unit-tested)

- `BillingCalendar.bookingMonth(date, months, today)`: the month of `date`, never after the current month. Open (or not
  existing yet after the last closed month) -> that month. Closed (months close in order, so a missing month before the
  last closed one counts as closed) -> the earliest open month after it, which is always the month right after the last
  closed one (opened when it has no row; a later month that has a row is never preferred). It is after the current
  month only when the current month itself is closed. The charge keeps its own service period and `accrued_on`. Tests:
  `BillingCalendarTests`.
- `DebtCalculator.of(lines)`: debt per charge type = the sum of the plot's charges of the type, reversals negative;
  total = their sum; the oldest unpaid accrual = the oldest `accrued_on` among positive charges that were not reversed
  (until payments exist). Exact `Money` addition, no rounding. Tests: `DebtCalculatorTests`.
- `BillingMonths.openForBooking(tenant, date)` (inside the caller's transaction, `MANDATORY`; every booking path goes
  through it): asks the calendar with the months' current statuses (scalars, never stale managed entities), inserts the
  month `ON CONFLICT DO NOTHING` when missing, locks it `FOR SHARE` and reads its status as a scalar under the lock, so a
  closing (`FOR UPDATE`) waits for the booking or the booking waits for the closing; a month found closed under the
  lock makes it ask the calendar again.

## Endpoints (tag `charges`)

| Endpoint | Operation | Who |
|---|---|---|
| `GET /plots/{id}/account?from&to` | `getPlotAccount` | `CHARGES_VIEW` for any plot, otherwise a current owner of the plot; anyone else, an unknown plot and one of another cooperative the same 404 `PLOT_NOT_FOUND`; the platform operator 403 |
| `POST /charges/{id}/reverse` | `reverseCharge` | `CHARGES_REVERSE` |
| `POST /plots/{id}/opening-balances` | `enterOpeningBalance` | `CHARGES_RUN` |

- **Account** (`AccountQueries`, one read-only transaction): `debts[]` lists every charge type the plot has a charge of,
  in the order of the charge types, with its debt (also `0.00` after a reversal); `total`; `charges[]` newest first by
  `accrued_on`, then booking time; `from`/`to` (inclusive, on `accrued_on`) limit the list, never the debts; `from`
  after `to` is 400 `INVALID` on `from`. Each charge carries its billing month (`YYYY-MM`) and whether it is closed,
  `reverses`/`reversedBy`, `reason`, the stored `calculation` (a charge stored without one shows its amount with no
  parts), `createdAt`, `createdByName` (the author's current name through `audit.ActorNames`, only for `CHARGES_VIEW`)
  and `reversible` (the viewer has `CHARGES_REVERSE`, the charge is not a reversal, not reversed and its month is open).
- **Reversal** (`Reversals`): reason trimmed and required (400 `REQUIRED`, `TOO_LONG` over 500); unknown charge 404
  `NOT_FOUND`; a reversal 409 `REVERSAL_NOT_REVERSIBLE`; the original's month is locked `FOR SHARE` and a closed one is
  409 `CHARGE_IN_CLOSED_MONTH` (its correction is an adjustment, feature 012); reversed before 409
  `CHARGE_ALREADY_REVERSED` (a pre-check, and the unique index for two reversals at once). The new row: kind `REVERSAL`,
  source `MANUAL_REVERSAL`, the negated amount (`Money.negate`), the same plot, charge type, service period and
  `accrued_on`, booked in the original's month (through `BillingMonths`), calculation `{amount, parts: [], readings: []}`.
  Answer 201 with the reversal.
- **Opening balance** (`OpeningBalances`): amount > 0 (400 `AMOUNT_NOT_POSITIVE`), at most 12 integer digits (400
  `INVALID`), date not after today in Europe/Kyiv (400 `DATE_IN_FUTURE`); the plot locked `FOR SHARE` (404
  `PLOT_NOT_FOUND`, 409 `PLOT_ARCHIVED`, 409 `PLOT_COMMON_LAND`); the charge type locked `FOR SHARE` (404 `NOT_FOUND` with
  `errors[{field: chargeTypeId}]`, 409 `CHARGE_TYPE_ARCHIVED`). The row: kind and source `OPENING`, service period and
  `accrued_on` = the date, booked by `BillingMonths` (the month of the date, or the earliest open month after it).
  The optional reason (at most 500, trimmed, blank means none) is stored on the charge (`reason` in the account) and
  in the audit entry. Answer 201 with the charge.

Lock order: plot `FOR SHARE` -> charge type `FOR SHARE` -> billing month `FOR SHARE`. The ledger never locks a `charge`
row (`FOR SHARE` would need `UPDATE`, which `nadel_app` does not have); concurrency on charges is settled by unique
indexes.

## Billing months (tag `billing`, slice 2)

| Endpoint | Operation | Who |
|---|---|---|
| `GET /billing-months` | `listBillingMonths` | `CHARGES_VIEW` |
| `GET /billing-months/{month}` | `getBillingMonth` | `CHARGES_VIEW` |
| `POST /billing-months/{month}/close` | `closeBillingMonth` | `PERIODS_CLOSE` |

- **List** (`BillingMonthQueries.list`): every month from the first `billing_month` row to the current month in
  Europe/Kyiv (or to the last row when a late booking opened a later one), newest first, also months without a row
  (`OPEN`, no totals; a read never creates a row). Per month: `status`, `runDone` (`run_at` set), `closedAt`, `totals`
  per charge type in the order of the charge types (`SUM(amount)` of the charges booked in the month, reversals
  negative; code, stored name and `nameCustomized` like the account) and `total`. No month at all: `[]`.
- **Month** (`details`): the same plus `closedByName` (through `audit.ActorNames`) and `checklist{runDone,
  notChargedCount, staleMeters[]}`: `notChargedCount` is 0 until the queue of slice 3 fills `notChargedCount(month)`;
  `staleMeters` are `meters.MeterNetwork.staleMeters(asOf, 60)` with `asOf` = the month's last day or today, whichever is
  earlier, with plot numbers, in the natural order of the numbers, electricity before water. A month that is not listed
  is 404 `NOT_FOUND`; a malformed month 400.
- **Close** (`MonthClosing`): 409 `PERIOD_IN_FUTURE` for a month after the current one; 404 `NOT_FOUND` before the first
  month (or with no month); a listed month without a row is inserted; the month is locked `FOR UPDATE` (status read as a
  scalar); 409 `PERIOD_CLOSED` when closed; 409 `PERIOD_CLOSE_ORDER` unless every earlier listed month has a `CLOSED`
  row; then `status = CLOSED`, `closed_at`, `closed_by` (native update, the persistence context cleared) and the audit
  entry. The check list never blocks closing. Answer 200 with the month as `GET` shows it.

Lock order of the slice: plot `FOR SHARE` -> charge type `FOR SHARE` -> billing month `FOR SHARE` (bookings) or
`FOR UPDATE` (closing). The status refresh runs in its own later transaction.

## The month run (tag `billing`, slice 3)

| Endpoint | Operation | Who |
|---|---|---|
| `POST /billing-months/{month}/run?preview=true\|false` | `runBillingMonth` | `CHARGES_RUN` (the preview too) |

- **What it charges** (`MonthRun`, `TariffMonthPlanning`): every active plot that is not common land
  (`PlotCatalog.findChargeable`) times every charge type that is active, not archived and has a tariff. Per plot and
  type `tariffs.TariffResolver` resolves over the month (`tariffs.TariffBook` snapshot): one tariff -> its charge, two
  or more -> a conflict, none -> nothing. The amount is `tariffs.ChargeCalculator.breakdown(versions, area, month,
  applicable days)`, rounded once; `0.00`, a tariff charged by meter readings (`PER_KWH`, `PER_M3`, any such version in
  the month) and no applicable day create nothing. The applicable days are `DaySet.ALL` now; `MonthRun.applicableDays`
  is the seam where slice 5 puts the water connection days.
- **The charge:** kind `REGULAR`, source `TARIFF`, `tariff_id`, `source_key` `TARIFF:{tariff}:{plot}:{YYYY-MM}`, booked
  in the run month, service period = the first to the last day the versions count for, `accrued_on` = the first day of
  the month (a one-time fee: its due date), `created_by` the person who ran it, `calculation` `{amount, parts[{tariffVersionId,
  basis, frequency, quantity, unit SOTKA|PLOT, rate, days, from, to}], readings: []}` and one `charge_part` per
  version (`days`, `units_num` = sotkas or 1, `units_den` = 1, `rate`). A one-time fee's part has 1 day, its due date.
- **Idempotent:** a key that a charge has (reversed or not) is never booked again, so a rerun creates only what is
  missing (a plot or tariff added later) and a reversed tariff charge is not re-created (its correction is feature
  012). Runs of one month are serialised by a transaction advisory lock (`ledger.month-run:{tenant}:{YYYY-MM}`).
- **Conflicts** go to the queue: item `TARIFF_MONTH`, key `TARIFF_MONTH:{plot}:{chargeType}:{YYYY-MM}`, reason
  `CONFLICTING_TARIFFS`, `params.tariffIds` all tariffs involved; an open item of the key is refreshed, never
  duplicated. A conflict whose tariffs would all charge `0.00` (for example two one-time fees due in other months) is not
  queued. **Only conflicts are queued by the month run:** a plot outside every tariff of a type, or excluded, is not a
  "not charged" case for recurring fees (it is for electricity readings, slice 4).
- **The run also closes** the open `TARIFF_MONTH` items of its month: charged by this run or before -> `RESOLVED`,
  nothing left to charge (no tariff applies any more, the plot is archived, the type inactive, `0.00`) -> `OBSOLETE`, a
  conflict that remains stays open with the tariffs of this run. `INTERVAL` items join with slice 4.
- **Preview and booking** run the same code. A preview writes nothing (not even the month row) and answers `{month,
  preview, plots (with at least one new charge), totals per charge type (new charges only, in type order), total,
  notCharged[] (the conflicts, with the id of an open item), created: 0, digest}`. The digest is SHA-256 over the
  planned new charges (source key, amount, service period, sorted by key). A booking answers the same with `created`;
  with a `digest` that differs from the one it would produce now it is 409 `PREVIEW_OUTDATED` and writes nothing.
- **Refusals:** a month after the current one in Europe/Kyiv 409 `PERIOD_IN_FUTURE` (preview too); a closed month, or
  a missing month before the last closed one, 409 `PERIOD_CLOSED` (preview too). A malformed month or a missing
  `preview` is 400.
- **Booking:** the month row is opened when missing, `run_at` is set (also when nothing new was charged), audit
  `BILLING_MONTH_RUN` (entity type `BILLING_MONTH`, label `YYYY-MM`, values `count` and `total`), `ChargesBooked` for
  the plots charged.
- **Locks** (`TariffChargeWriter`): the month's advisory lock -> the charge types `FOR SHARE` -> every tariff
  `FOR SHARE` (`TariffBook.lockShared`, id order) -> the billing month `FOR SHARE` (`BillingMonths.openMonthForRun`).
  A correction or deletion of a version or a change of scope holds its tariff `FOR UPDATE`, so it waits for a run and
  then sees its charges. The snapshot is read after the locks.

## The "not charged" queue (tag `not-charged`, slice 3)

| Endpoint | Operation | Who |
|---|---|---|
| `GET /not-charged` | `listNotCharged` | `CHARGES_VIEW` |
| `POST /not-charged/{id}/retry` | `retryNotCharged` | `CHARGES_RUN` |

- **List** (`NotChargedQueue.list`, `NotChargedViews`): the open items, newest month (or range end) first, then the
  natural order of the plot numbers, then the charge types. Each with `id`, `kind`, `reason`, `plotId`, `plotNumber`,
  `chargeType` (code, stored name, `nameCustomized`), `meterId`, `month`, `from`, `to`, `tariffs[{id, name}]` (by
  name; the link to fix) and `createdAt`.
- **Retry** of a `TARIFF_MONTH` item: the month's advisory lock, the item `FOR UPDATE`, the charge type and the
  tariffs `FOR SHARE`, then `TariffMonthPlanning` for the plot, type and month. A new charge is booked through
  `BillingMonths.openForBooking`: in the month, or, when it was closed meanwhile, in the earliest open month after it
  (the charge keeps its service period and `accrued_on`); the item is `RESOLVED` (also when the charge exists already),
  `OBSOLETE` when nothing is left to charge, and stays open (reason and tariffs refreshed) while the conflict remains.
  Answer `{resolved, item?}`; an item that is not open answers `{resolved: true}` and changes nothing; an unknown item
  or one of another cooperative is 404 `NOT_FOUND`. Other kinds are retried by their own slices. Audit
  `NOT_CHARGED_RETRIED` (entity type `CHARGE` about the plot; `kind`, `reason`, `chargeType`, `month`, `outcome`
  `RESOLVED`/`OBSOLETE`/`OPEN`).
- **Check list:** `notChargedCount` of a month counts its open items: those of the month itself and those without a
  month whose `to_date` falls in it.

## Tariff versions in use

`LedgerTariffVersionUsage` implements `tariffs.TariffVersionUsage`: a version is used while a charge that is not
reversed has a `charge_part` priced by it, so its correction or deletion is 409 `TARIFF_VERSION_IN_USE`; after the
reversal of those charges it is free again. It replaces the stub `tariffs/internal/NoChargesYetUsage`.

## Plot status

`PlotStatusRefresher` listens to `ChargesBooked` with the ADR 0007 pattern (`@Async @TransactionalEventListener`, no
`@Transactional`; `TenantContext.runAs(event.tenantId())`, then a `TransactionTemplate`): `plots.PlotDebtCache.lockAll`
(the cache rows `FOR UPDATE` in id order), then the committed charges of the plots summed by `DebtCalculator`, then
`record(plot, total, oldestAccrual)` per plot. Idempotent (it recomputes from the ledger), so a redelivered event writes
the same values; logs counts only. The colour itself is derived by `plots` when it is read (see the plots README), so a
change of the red threshold needs no refresh. Tests: `PlotStatusRefreshTests` (they wait for the asynchronous listener).

## Plot guard

`LedgerPlotGuard` implements `plots.PlotChangeGuard`: a plot that has any charge (reversed or not) is not deleted, 409
`PLOT_HAS_CHARGES` (the foreign key `charge_plot_fk` is the backstop). Archiving and turning into common land are not
refused here.

## Audit

Entity type `CHARGE`, about the plot whose account changed: the entity id is the plot id (the log links to the plot
card), the label the plot number. Values are codes and parameters (`LedgerAuditPublisher`):

- `CHARGE_REVERSED`: `chargeType` (`base:<CODE>` while a seeded type keeps its default name, otherwise the stored name),
  `amount` (the reversal, `-25.00`), `serviceFrom`, `serviceTo`, `billingMonth` (`YYYY-MM`), `reason`.
- `OPENING_BALANCE_ENTERED`: `chargeType`, `amount`, `date`, `billingMonth`, `reason` (left out when blank).

Entity type `BILLING_MONTH` (`LedgerAuditPublisher.publishMonth`), about the month row, label the month `YYYY-MM`:

- `BILLING_MONTH_CLOSED`: `status` `OPEN` -> `CLOSED`.
- `BILLING_MONTH_RUN`: `count` (charges written) and `total` (their sum).

Entity type `CHARGE` about the plot, as above:

- `NOT_CHARGED_RETRIED`: `kind`, `reason`, `chargeType`, `month`, `outcome`.

The charges themselves are the ledger and are not repeated in the log. Reasons are user content: never logged.

Every action is a constant of `LedgerAuditPublisher.Action` and must also be a value of `AuditAction` in
`api/openapi.yaml`: the audit listing maps stored entries with `AuditAction.fromValue`, so a missing value breaks it
(guarded by `LedgerAuditActionsTests`).

## Dependencies

`tariffs` (the read facade `ChargeTypeCatalog`: `find`, `findAndLockShared`, `all`; `TariffBook`: `snapshot`,
`tariffIds`, `lockShared`; the pure `TariffResolver`, `ChargeCalculator.breakdown` and `DaySet`; the port
`TariffVersionUsage`, implemented here), `plots` (`PlotCatalog`: `find`, `findAll`, `findAndLockShared`,
`inNaturalOrder`, `findChargeable`; the SPI `PlotChangeGuard`; the debt cache writer `PlotDebtCache`),
`meters` (`MeterNetwork.staleMeters`), `money` (`Money`), `identity` (`CurrentUser`, `AccessProfile`, `Permission`,
`OwnedPlotsSource`), `audit` (events, `ActorNames`), `problem`, `tenancy` (`TenantContext`). Nothing depends on
`ledger`.

## Permissions

`CHARGES_VIEW` (Chairman, Treasurer): every plot's account, the months and the queue, and later the runs.
`CHARGES_RUN` (Chairman): opening balances, the month run (and its preview) and retries, and later losses and common
consumer runs. `CHARGES_REVERSE`
(Chairman): reversing a charge, and later a run. `PERIODS_CLOSE` (Chairman): closing a month. Defined in
`identity.Permission`, seeded by `seed_base_roles` (V13; existing cooperatives by targeted inserts).

## Tests

`BillingCalendarTests`, `DebtCalculatorTests` (pure); `MonthRunTests` (the month-run rows of §3: preview, booking,
rerun, a plot added later, 0.00, a reversed charge, one-time fees, scopes, inactive types, tariffs by readings,
conflicts to the queue naming both tariffs, a stale digest, closed and future months, permissions, audit, plot debt),
`NotChargedQueueTests` (list order, retry unfixed, fixed, nothing left, a closed month, auto-closing by the run,
404, permissions, audit), `TariffVersionUsageTests` (409 after the run, accepted after the reversal),
`TariffConflictWarningTests` (the tariffs page), `NotChargedTenantIsolationTests` (V15) - these run in a cooperative of
their own (`FreshCooperativeSupport`); `BillingMonthsTests` (the list and totals, the month page and its
check list, closing in order, a closed month that gets nothing more, late bookings, future months, permissions, audit,
the `billing_month_guard` trigger); `PlotStatusRefreshTests`; `LedgerAccountTests` (the account, debts per type, the reversal
rules of §3, opening balances, booking after a closed month, the plot guard, allowed and denied per endpoint for the
chairman, the treasurer, custom roles, a member for their own and another plot, the other cooperative and the platform
operator); `LedgerAuditTests` (entries and `ChargesBooked`); `LedgerTenantIsolationTests` and `LedgerAppendOnlyTests`
(the tables); `LedgerMigrationTests` (V13 on existing cooperatives); `ChargesBookedTests`. Test helpers clear ledger rows
and billing months as `nadel_owner` with the maintenance setting (`LedgerTestSupport`).
