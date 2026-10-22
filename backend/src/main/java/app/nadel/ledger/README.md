# ledger

The ledger of the cooperative (feature 011): the **charges** of plots, the **billing months** they are booked in,
**reversals**, **opening balances** and the **account** of a plot with the debt per charge type. Slice 1 of spec 011 is
in code; the month run, charges from readings, closing months, losses and common consumer runs, the not-charged queue,
water connections and the plot status follow in the next slices.

Tables (V13, all under RLS `tenant_isolation`, an index leading with `tenant_id`, composite foreign keys on
`(tenant_id, id)`):

| Table | What it holds |
|---|---|
| `billing_month` | An accounting period: `month` (its first day), `status` `OPEN` or `CLOSED`, `run_at`, `closed_at`, `closed_by`. Created on demand, `OPEN` by default; never deleted (`DELETE`/`TRUNCATE` revoked) |
| `charge` | The ledger. `kind` `REGULAR`/`REVERSAL`/`OPENING`, `source` `TARIFF`/`CONSUMPTION`/`LOSSES`/`COMMON_CONSUMER`/`OPENING`/`MANUAL_REVERSAL`, `amount numeric(14,2)`, service period `service_from..service_to`, `accrued_on` (the age of the debt), `billing_month_id`, `calculation jsonb` (codes and numbers only), `source_key` (idempotency of later runs), `reverses_charge_id` (only on a reversal), `reason` (required on a reversal, optional on other kinds, for example an opening balance), `created_by`, `created_at` |
| `charge_part` | The tariff versions behind a charge (days, an exact quantity `units_num/units_den`, the rate); written from slice 3 |
| `meter_value_use` | The meter values a charge or run read; written from slice 4 |

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
| `ChargesBooked(tenantId, plotIds)` | Published inside the transaction of every booking or reversal (now: a reversal and an opening balance). The plot status listener of slice 2 recomputes the debt of those plots from the committed ledger. Ids only, no personal data; the set is never empty |

Everything else is in `internal`.

## Rules (pure, unit-tested)

- `BillingCalendar.bookingMonth(date, months, today)`: the month of `date`, never after the current month. Open (or not
  existing yet after the last closed month) -> that month. Closed (months close in order, so a missing month before the
  last closed one counts as closed) -> the earliest open month after it that is not after the current month, else a new
  month right after the last closed one. The charge keeps its own service period and `accrued_on`. Tests:
  `BillingCalendarTests`.
- `DebtCalculator.of(lines)`: debt per charge type = the sum of the plot's charges of the type, reversals negative;
  total = their sum; the oldest unpaid accrual = the oldest `accrued_on` among positive charges that were not reversed
  (until payments exist). Exact `Money` addition, no rounding. Tests: `DebtCalculatorTests`.
- `BillingMonths.openForBooking(tenant, date)` (inside the caller's transaction, `MANDATORY`): asks the calendar,
  inserts the month `ON CONFLICT DO NOTHING` when missing, and returns it locked `FOR SHARE`, so a closing (slice 2,
  `FOR UPDATE`) waits for the booking; a month found closed under the lock makes it ask the calendar again.

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

The charges themselves are the ledger and are not repeated in the log. Reasons are user content: never logged.

## Dependencies

`tariffs` (the read facade `ChargeTypeCatalog`: `find`, `findAndLockShared`, `all`), `plots` (`PlotCatalog`: `find`,
`findAndLockShared`; the SPI `PlotChangeGuard`), `money` (`Money`), `identity` (`CurrentUser`, `AccessProfile`,
`Permission`, `OwnedPlotsSource`), `audit` (events, `ActorNames`), `problem`. Nothing depends on `ledger`.

## Permissions

`CHARGES_VIEW` (Chairman, Treasurer): every plot's account, and later the months, runs and the queue. `CHARGES_RUN`
(Chairman): opening balances, and later the month run, losses and common consumer runs and retries. `CHARGES_REVERSE`
(Chairman): reversing a charge, and later a run. `PERIODS_CLOSE` (Chairman): closing a month (slice 2). Defined in
`identity.Permission`, seeded by `seed_base_roles` (V13; existing cooperatives by targeted inserts).

## Tests

`BillingCalendarTests`, `DebtCalculatorTests` (pure); `LedgerAccountTests` (the account, debts per type, the reversal
rules of §3, opening balances, booking after a closed month, the plot guard, allowed and denied per endpoint for the
chairman, the treasurer, custom roles, a member for their own and another plot, the other cooperative and the platform
operator); `LedgerAuditTests` (entries and `ChargesBooked`); `LedgerTenantIsolationTests` and `LedgerAppendOnlyTests`
(the tables); `LedgerMigrationTests` (V13 on existing cooperatives); `ChargesBookedTests`. Test helpers clear ledger rows
as `nadel_owner` with the maintenance setting (`LedgerTestSupport`).
