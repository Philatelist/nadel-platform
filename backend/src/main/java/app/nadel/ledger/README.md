# ledger

The ledger of the cooperative (feature 011): the **charges** of plots, the **billing months** they are booked in,
**reversals**, **opening balances**, the **account** of a plot with the debt per charge type, the **month run** of
recurring tariffs, the **charges from meter readings**, the **proportional losses runs**, the **common consumer runs**
and the **"not charged" queue**. Slices 1 to 7 of spec 011 are in code (slice 2: the billing months with their totals
and check list, closing in order, and the plot status refresh; slice 3: the month run and the queue; slice 4: a charge
per interval of a plot meter and fixed-percentage losses; slice 5: water tariffs charge only the days a plot is
connected to water; slice 6: proportional losses runs; slice 7: common consumer runs and the split of each consumer).

Tables (V13, all under RLS `tenant_isolation`, an index leading with `tenant_id`, composite foreign keys on
`(tenant_id, id)`):

| Table | What it holds |
|---|---|
| `billing_month` | An accounting period: `month` (its first day), `status` `OPEN` or `CLOSED`, `run_at`, `closed_at`, `closed_by`. Created on demand, `OPEN` by default; never deleted (`DELETE`/`TRUNCATE` revoked). The V14 trigger `billing_month_guard` refuses any change of a closed month (so no reopening), a change of `month`, and every `DELETE`/`TRUNCATE`, to everyone but `nadel_owner` with the maintenance setting |
| `charge` | The ledger. `kind` `REGULAR`/`REVERSAL`/`OPENING`, `source` `TARIFF`/`CONSUMPTION`/`LOSSES`/`COMMON_CONSUMER`/`OPENING`/`MANUAL_REVERSAL`, `amount numeric(14,2)`, service period `service_from..service_to`, `accrued_on` (the age of the debt), `billing_month_id`, `calculation jsonb` (codes and numbers only), `source_key` (idempotency of later runs), `reverses_charge_id` (only on a reversal), `reason` (required on a reversal, optional on other kinds, for example an opening balance), `created_by`, `created_at` |
| `charge_part` | The tariff versions behind a charge (days, an exact quantity `units_num/units_den`, the rate); written by the month run (one row per version) |
| `meter_value_use` | The meter values a charge or run read: one row per point date (an interval charge: its two points). Not a ledger table. Rows stay after a reversal; `LedgerReadingUsage` leaves out the rows of reversed charges |
| `losses_run` (V17) | A proportional losses run: `from_date`, `to_date`, `status` `BOOKED`/`REVERSED`, `billing_month_id` (its charges' month), `total`, `created_by`, `created_at`, `reversed_at`, `reversed_by`, `reverse_reason`. Its charges are `charge` rows with `run_id` and source `LOSSES` |
| `losses_run_node` (V17) | A node of a run as booked: `node_meter_id` (the node's chain, its first meter), `node_kind`, `node_name`, the run's range and status repeated (`from_date`, `to_date`, `run_status`), `position`, `outcome` `CHARGED`/`NEGATIVE`/`UNKNOWN`/`SKIPPED_ABOVE`, `consumption`, `children`, `losses` (kWh, three decimals for display), `pct`, `calculated`, `amount`, `warning jsonb`, `plots jsonb` (codes, ids and numbers only). The exclusion constraint `losses_run_node_no_overlap_ex` keeps two booked runs from charging a node for the same day (only `BOOKED` and `CHARGED` rows) |
| `common_consumer_run` (V18) | A common consumer run: the columns of `losses_run`. Its charges are `charge` rows with `run_id` and source `COMMON_CONSUMER` |
| `common_consumer_run_line` (V18) | A common consumer of a run as booked: `consumer_meter_id` (the consumer's chain, its first meter; no foreign key, so a meter entered by mistake can still be deleted after a run listed it as unknown), `consumer_name`, the run's range and status repeated, `position`, `split`, `outcome` `CHARGED`/`NOT_CHARGED`/`NO_PLOTS`/`UNKNOWN`, `consumption` (kWh, three decimals for display), `calculated`, `plot_days`, `amount`, `warning jsonb`, `plots jsonb` (`plotId`, `days`, `kwh`, `amount?`, `reason?`). The exclusion constraint `common_consumer_run_line_no_overlap_ex` keeps two booked runs from charging a consumer for the same day (only `BOOKED` and `CHARGED` rows). Both run tables change only by the reversal (`run_reversal_guard`, V17) |
| `common_consumer_billing` (V18) | Settings: the split of one common consumer meter (`CONNECTED_PLOTS`/`EQUAL_PER_PLOT`/`SERVICE_USERS`/`NOT_CHARGED`); no row = the cooperative's `common_consumer_default_split`. Changed and deleted by the application; goes with its meter (`ON DELETE CASCADE`) |
| `common_consumer_user` (V18) | Settings: the plots marked as using a common consumer (`SERVICE_USERS`); PK `(tenant_id, consumer_meter_id, plot_id)`; goes with its meter and its plot |
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
| `ChargesBooked(tenantId, plotIds)` | Published inside the transaction of every booking or reversal (a reversal, an opening balance, the month run, a retry and the charges of readings, at most 500 plots per event; every later booking path must publish it too). `PlotStatusRefresher` recomputes the debt of those plots from the committed ledger. Ids only, no personal data; the set is never empty |

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
  type `tariffs.TariffResolver` finds the tariffs that apply over the month (`tariffs.TariffBook` snapshot). When the
  month's charge of any of them exists (reversed or not) there is nothing new. Otherwise each one's amount is
  `tariffs.ChargeCalculator.breakdown(versions, area, month, applicable days)`, rounded once, and **only the tariffs
  that would charge more than `0.00` take part** (money rules §4.1): one -> its charge, two or more -> a conflict, none
  -> nothing (`0.00`, a tariff charged by meter readings (`PER_KWH`, `PER_M3`, any such version in the month), no
  applicable day). So a one-time fee is charged in its due month although a fee of the same type due in another month
  applies too. The applicable days (`WaterDays`) are every day, except for the `WATER` charge type: its tariffs, of
  any basis, count only the days the plot is connected to water (`meters.WaterConnections.connectedDays`, read once
  per run for every chargeable plot and every month the run plans), so a plot that is not connected in the month gets
  no water charge and is never queued, and a connection that starts or ends inside the month splits the fee by days
  (300.0000 a year from the 16th of October: 12.90). A retry of a month item uses the same days.
- **The charge:** kind `REGULAR`, source `TARIFF`, `tariff_id`, `source_key` `TARIFF:{tariff}:{plot}:{YYYY-MM}`, booked
  in the run month, service period = the first to the last day the versions count for, `accrued_on` = the first day of
  the month (a one-time fee: its due date), `created_by` the person who ran it, `calculation` `{amount, parts[{tariffVersionId,
  basis, frequency, quantity, unit SOTKA|PLOT, rate, days, from, to}], readings: []}` and one `charge_part` per
  version (`days`, `units_num` = sotkas or 1, `units_den` = 1, `rate`). A one-time fee's part has 1 day, its due date.
- **Idempotent:** a key that a charge has (reversed or not) is never booked again, so a rerun creates only what is
  missing (a plot or tariff added later) and a reversed tariff charge is not re-created (its correction is feature
  012). Runs of one month are serialised by a transaction advisory lock (`ledger.month-run:{tenant}:{YYYY-MM}`).
- **Conflicts** go to the queue: item `TARIFF_MONTH`, key `TARIFF_MONTH:{plot}:{chargeType}:{YYYY-MM}`, reason
  `CONFLICTING_TARIFFS`, `params.tariffIds` the tariffs that would charge more than `0.00`; an open item of the key is
  refreshed, never duplicated. A plot that already has the month's charge of one of the tariffs (a tariff whose scope
  grew after the run) is not queued for that month. **Only conflicts are queued by the month run:** a plot outside every tariff of a type, or excluded, is not a
  "not charged" case for recurring fees (it is for electricity readings, slice 4).
- **The run also closes** the open `TARIFF_MONTH` items of its month: charged by this run or before -> `RESOLVED`,
  nothing left to charge (no tariff applies any more, the plot is archived, the type inactive, `0.00`) -> `OBSOLETE`, a
  conflict that remains stays open with the tariffs of this run. A booking also retries the open `INTERVAL` items of
  every month (`IntervalCharging.retryAll`, see Charges from readings), right after the month's advisory lock.
- **The run retries the open items of every other month** (`NotChargedRetries`, the same path as "Повторити"): each
  open `TARIFF_MONTH` item is planned again with `TariffMonthPlanning` for its plot, type and own month (a plot that is
  no longer chargeable has nothing left). A new charge is booked through `BillingMonths.openForBooking`: in the item's
  month, or, when it is closed, in the earliest open month after it (with the item month's service period, source key
  and `accrued_on`); the item becomes `RESOLVED` (also when the charge exists already) or `OBSOLETE`, or stays open
  with the reason and tariffs of this run.
- **Preview and booking** run the same code. A preview writes nothing (not even the month row) and answers `{month,
  preview, plots (with at least one new charge), totals per charge type (new charges only, in type order), total,
  notCharged[] (the conflicts of the month, with the id of an open item), created: 0, digest}`. The new charges are
  those of the month and those of the retried items of other months: both count in `plots`, `totals`, `total`,
  `created` and the digest; `notCharged` lists only the month's own conflicts (an earlier item that stays open is in
  the queue). The digest is SHA-256 over the planned new charges (source key, amount, service period, sorted by key).
  A booking answers the same with `created`; with a `digest` that differs from the one it would produce now it is 409
  `PREVIEW_OUTDATED` and writes nothing.
- **Refusals:** a month after the current one in Europe/Kyiv 409 `PERIOD_IN_FUTURE` (preview too); a closed month, or
  a missing month before the last closed one, 409 `PERIOD_CLOSED` (preview too). A malformed month or a missing
  `preview` is 400.
- **Booking:** the month row is opened when missing, `run_at` is set (also when nothing new was charged), audit
  `BILLING_MONTH_RUN` (entity type `BILLING_MONTH`, label `YYYY-MM`, values `count` and `total`, the retried items'
  charges included), `ChargesBooked` for the plots charged.
- **Locks** (`TariffChargeWriter`): the month's advisory lock (`ledger.month-run:{tenant}:{YYYY-MM}`) -> the queue
  advisory lock (`ledger.not-charged:{tenant}`) -> every charge type `FOR SHARE` (id order) -> every tariff
  `FOR SHARE` (`TariffBook.lockShared`, id order) -> the billing month `FOR SHARE` (`BillingMonths.openMonthForRun`)
  -> the months of the retried charges `FOR SHARE` (`openForBooking`, in calendar order). A retry takes the same locks
  (its item's month, the queue, the item `FOR UPDATE`, the types and tariffs). The queue lock serialises every booking
  run and retry of the cooperative, so a run that books an item of another month never races that month's run or a
  retry of the item: nobody takes a month advisory lock while holding the queue lock and each transaction takes one
  month lock, so the advisory locks cannot deadlock; a closing takes one month `FOR UPDATE`, so the shared month locks
  cannot either. A correction or deletion of a version or a change of scope holds its tariff `FOR UPDATE`, so it waits
  for a run and then sees its charges. The snapshot is read after the locks.

## Proportional losses runs (tag `losses-runs`, slice 6)

| Endpoint | Operation | Who |
|---|---|---|
| `POST /losses-runs?preview=true\|false` | `runLosses` | `CHARGES_RUN` (the preview too) |
| `GET /losses-runs` | `listLossesRuns` | `CHARGES_VIEW` |
| `GET /losses-runs/{id}` | `getLossesRun` | `CHARGES_VIEW` |
| `POST /losses-runs/{id}/reverse` | `reverseLossesRun` | `CHARGES_REVERSE` |

- **The network** (`meters.MeterNetwork.network(from, to)`; a booking `lockSharedNetwork`, every electricity meter
  `FOR SHARE` in id order): every chain of electricity meters that worked in the range (a replacement never splits a
  node), the histories of its meters and its places in the tree by days, through the day of the final removal.
- **The losses** (`LossesCalculator`, pure): the days `from` to `to` are counted with end-of-day values, so the
  consumption of a meter over days `a..b` is `valueAt(b) - valueAt(a - 1)` and back-to-back runs neither overlap nor
  leave a gap. A node is a substation or quarter chain with a meter under it on a day of the range. Per node: losses =
  its consumption - its children's consumption over the days they were under it (plot meters, common consumers, quarter
  meters); `UNKNOWN` when its own or a child's value is unknown (no reading after the date); `SKIPPED_ABOVE` when a node
  below it is unknown; `NEGATIVE` when the losses are below zero (nothing is charged; losses that are 0.000 kWh at
  three decimals, the scale they are stored and shown at, count as zero: `LossesCalculator.settled`); otherwise
  `CHARGED`: the losses are shared among the plots under it (directly or through child nodes, within the days those
  were under it) in proportion to each plot's consumption under it, exactly; a plot with zero consumption gets nothing.
  An interpolated value marks the node `calculated`. The values a charged node read are listed (the points at, or on
  both sides of, the end of the day before each slice and the end of each slice).
- **The price** (`LossesPlanning`, pure): a plot's kWh from every charged node are added and priced once with its
  electricity tariff over the range (`TariffResolver`; `ReadingChargeCalculator` spreads the kWh evenly over the days,
  split by days when the rate changes, one rounding); one `LOSSES` charge per plot. A plot that cannot be priced is
  listed with `reason` (`NO_TARIFF_FOR_ALL_DAYS`, `CONFLICTING_TARIFFS`, `OUTSIDE_SCOPE`, `EXCLUDED`) and not charged;
  an inactive type or `0.00` charges nothing. Each node's share is also priced on its own for the node's `amount`.
- **The answer** `{runId?, from, to, preview, billingMonth, nodes[{meterId, kind, name, consumption, children, losses,
  pct, calculated, outcome, warning{code, meterId, meterKind, meterName, plotNumber, lastReadingOn, runId, from, to},
  plots[{plotId, plotNumber, kwh, amount?, reason?}], amount}], charges[{plotId, plotNumber, kwh, amount, chargeId?,
  reversed?}], plots, total, created, digest}`. Nodes: substations first, then quarter meters, each by name. Warning
  codes: `NEGATIVE_LOSSES`, `UNKNOWN_VALUE`, `SKIPPED_ABOVE` (the node below), `ALREADY_CHARGED` (the booked run that
  charged the node for a day of the range; outcome `ALREADY_CHARGED`, charges nothing), `NO_PLOT_CONSUMPTION` (losses
  above zero, nobody under the node used anything).
- **Rules:** `to` before `from` 400 `INVALID`, after today 400 `DATE_IN_FUTURE` (field `to`); `nodeIds[i]` not a node
  of the range 400 `NOT_FOUND` (absent or empty: every node; any meter of a node's chain names it); the losses mode is
  not `PROPORTIONAL` 409 `LOSSES_MODE_NOT_PROPORTIONAL` (preview too). A booking with a selected node that is
  `ALREADY_CHARGED` is 409 `LOSSES_RANGE_OVERLAP` (book the others with `nodeIds`; the exclusion constraint is the
  backstop); with a `digest` that differs 409 `PREVIEW_OUTDATED`. The digest covers the nodes that are not
  `ALREADY_CHARGED` (outcome and numbers) and the charges (plot, exact kWh, amount), so a preview of every node matches
  a booking of the others.
- **Booking:** the run (`losses_run`) in the month of `to` or the earliest open month after it
  (`BillingMonths.openForBooking`), its nodes (`losses_run_node`), one charge per plot (kind `REGULAR`, source
  `LOSSES`, charge type `ELECTRICITY`, `tariff_id`, `run_id`, key `LOSSES_RUN:{run}:{plot}`, service period the range,
  `accrued_on` = `to`, `calculation` `{amount, parts[{tariffVersionId, basis, frequency, quantity, unit KWH, rate, days,
  from, to, nodeMeterId, calculated}], readings: [], runId, nodeMeterId (one node only)}`, one `charge_part` per node and
  version with `node_meter_id` and the exact kWh), one `meter_value_use` row (with `run_id`) per value the charged nodes
  read, an open item `LOSSES_NODE:{node}:{from}:{to}` (reason `UNKNOWN_NODE_VALUE`, `meter_id` the node, charge type
  `ELECTRICITY`) per `UNKNOWN` or `SKIPPED_ABOVE` node, settles the open items of a charged node whose range meets the
  run's (`LossesRuns.settle`: `RESOLVED` when the booked runs that charged the node cover every day of the item; else
  the item is `RESOLVED` and narrowed to the days left, an open item `LOSSES_NODE:{node}:{from}:{to}` per run of
  uncharged days) and closes the item of a negative node for the same range (`OBSOLETE`), audit `LOSSES_RUN_BOOKED`
  (entity type `LOSSES_RUN`, label `from/to`, values `from`, `to`, `billingMonth`, `nodes`, `count`, `total`) and
  `ChargesBooked`.
- **Reversal** (`reverse`): reason required (400 `REQUIRED`; `TOO_LONG` over 500); 404 `NOT_FOUND`; 409
  `RUN_ALREADY_REVERSED`; the run's month `FOR SHARE`, a closed one 409 `RUN_IN_CLOSED_MONTH`. Every charge of the run
  that is not reversed yet (one may have been reversed by hand) gets a `REVERSAL` row with the run's reason (source
  `MANUAL_REVERSAL`, the run's `run_id`, in the charge's month); the run becomes `REVERSED` with the time, person and
  reason, its nodes `run_status = REVERSED` (their days are free again, and its value uses no longer count). Audit
  `LOSSES_RUN_REVERSED` (`from`, `to`, `count`, `total` of the reversals, `reason`); `ChargesBooked`. The ledger stays
  append-only; the run rows change only by `BOOKED` -> `REVERSED` (trigger `run_reversal_guard`, V17).
- **History and details:** newest first, with `billingMonth`, `createdByName`, `total`, `plots` (charges),
  `chargedNodes`, `skippedNodes`, `reversible` (`CHARGES_REVERSE`, booked, open month) and the reversal; the details add
  the nodes as booked and the charges with their exact kWh (from their parts) and whether each was reversed.
- **Locks:** the cooperative's advisory lock of losses runs (`ledger.losses-runs:{tenant}`; bookings, reversals and
  retries one at a time) -> every electricity meter `FOR SHARE` -> the queue lock -> the charge types and tariffs
  `FOR SHARE` -> the billing month `FOR SHARE`: the order of the month run.
  A known, rare deadlock: a booking or retry locks every electricity meter `FOR SHARE` in id order
  (`MeterNetwork.lockSharedNetwork`), while the replacement of a substation or quarter meter (`meters` `MeterParents`)
  holds the old meter `FOR UPDATE` and then locks the meters under it `FOR UPDATE` in id order. When the run already
  holds a child whose id sorts before the old meter's and waits for the old meter, the two wait for each other;
  Postgres detects it and aborts one of them (SQLSTATE `40P01`, answered as a 500 with nothing written). It is
  recoverable: the aborted action is simply repeated. Not fixed in code: the replacement locks the old meter first (it
  has to read and check it before it knows its children), so locking it together with its children in id order would
  mean reading the children unlocked and re-checking them after the locks, more change than a rare, harmless abort is
  worth.

## Common consumer runs (tag `common-consumer-runs`, slice 7)

| Endpoint | Operation | Who |
|---|---|---|
| `POST /common-consumer-runs?preview=true\|false` | `runCommonConsumers` | `CHARGES_RUN` (the preview too) |
| `GET /common-consumer-runs` | `listCommonConsumerRuns` | `CHARGES_VIEW` |
| `GET /common-consumer-runs/{id}` | `getCommonConsumerRun` | `CHARGES_VIEW` |
| `POST /common-consumer-runs/{id}/reverse` | `reverseCommonConsumerRun` | `CHARGES_REVERSE` |
| `GET\|PUT /common-consumers/{meterId}/billing` | `getCommonConsumerBilling`, `updateCommonConsumerBilling` | `SETTINGS_MANAGE` |

- **The consumers:** every `COMMON_CONSUMER` chain of `MeterNetwork.network(from, to)` (a booking
  `lockSharedNetwork`), keyed by its first meter (a replacement keeps the consumer), ordered by name. Each is measured as
  a losses node is (`LossesCalculator.measure`: `valueAt(to) - valueAt(from - 1)`, interpolated values marked
  `calculated`, the points read listed).
- **The split** (`CommonConsumerSettings`): the split and service users of the latest meter of the chain that has
  settings, else the cooperative's `commonConsumerDefaultSplit` and no users. The plots that may share it are the
  chargeable plots (active, not common land, `PlotCatalog.findChargeable`): `EQUAL_PER_PLOT` all of them, `SERVICE_USERS`
  the marked ones, each with the days of the range; `CONNECTED_PLOTS` those connected to any meter of the chain
  (`WaterConnections.connectedToSource`) on their connected days; `NOT_CHARGED` none.
- **The kWh** (`CommonConsumerSplitCalculator`, pure): `CONNECTED_PLOTS` per plot-day (`consumption x plot's days /
  sum of the days`), `EQUAL_PER_PLOT` and `SERVICE_USERS` equally; exact quantities.
- **The price** (`CommonConsumerPlanning`, pure): each plot's kWh of a consumer priced with its `COMMON_CONSUMERS`
  tariff (`TariffResolver`; `ReadingChargeCalculator` spreads the kWh evenly over the plot's own days of the split,
  split by days when the rate changes, one rounding); one charge per plot per consumer. A share that cannot be priced is
  listed with `reason` and not charged; `0.00` charges nothing. `noTariff` when the type is inactive or archived or has
  no tariff with a `PER_KWH` version valid on a day of the range: the plots are listed without amounts.
- **Outcomes:** `NOT_CHARGED` (its split), `UNKNOWN` (warning `UNKNOWN_VALUE` with `meterId`, `lastReadingOn`),
  `NO_PLOTS` (used something, nobody shares it; warning `NO_PLOTS`; the days stay free), `CHARGED` (also with zero
  consumption), `ALREADY_CHARGED` in a preview (warning with the booked run, `from`, `to`).
- **Rules:** `to` before `from` 400 `INVALID`, after today 400 `DATE_IN_FUTURE` (field `to`); `consumerIds[i]` not a
  consumer of the range 400 `NOT_FOUND`. A booking without a tariff is 409 `NO_COMMON_CONSUMERS_TARIFF`, with a
  `digest` that differs 409 `PREVIEW_OUTDATED`, with a selected consumer that is `ALREADY_CHARGED` 409
  `COMMON_CONSUMER_RANGE_OVERLAP` (the exclusion constraint is the backstop). The digest covers whether there is a
  tariff, the consumers that are not `ALREADY_CHARGED` (outcome, split, consumption) and the charges.
- **Booking:** the run (`common_consumer_run`) in the month of `to` or the earliest open month after it, its lines,
  one charge per plot per consumer (kind `REGULAR`, source `COMMON_CONSUMER`, charge type `COMMON_CONSUMERS`,
  `tariff_id`, `run_id`, key `COMMON_CONSUMER_RUN:{run}:{consumer}:{plot}`, service period the range, `accrued_on` =
  `to`, `calculation` `{amount, parts[{tariffVersionId, basis, frequency, quantity, unit KWH, rate, days, from, to,
  calculated}], readings: [], runId, consumerMeterId, consumerName, split}`, one `charge_part` per version), one
  `meter_value_use` row (with `run_id`) per value a charged consumer read, an open item
  `COMMON_CONSUMER:{consumer}:{from}:{to}` (kind `COMMON_CONSUMER`, reason `UNKNOWN_CONSUMER_VALUE`, `meter_id` the
  consumer, charge type `COMMON_CONSUMERS`) per unknown consumer; closes the open items of a charged consumer whose
  range meets the run's (`RESOLVED`) and the item of a `NOT_CHARGED` or `NO_PLOTS` consumer for the same range
  (`OBSOLETE`); audit `COMMON_CONSUMER_RUN_BOOKED` (entity type `COMMON_CONSUMER_RUN`, label `from/to`, values `from`,
  `to`, `billingMonth`, `consumers`, `count`, `total`) and `ChargesBooked`.
- **Reversal, history and details:** as for losses runs (`RUN_ALREADY_REVERSED`, `RUN_IN_CLOSED_MONTH`, reason
  required; audit `COMMON_CONSUMER_RUN_REVERSED` with `from`, `to`, `count`, `total`, `reason`). The summary counts
  `plots`, `charges`, `chargedConsumers` and `skippedConsumers`; the details give each plot share its `chargeId` and
  `reversed`.
- **Retry** of a `COMMON_CONSUMER` item (`CommonConsumerRuns.retry`, under the locks of a booking, the item
  `FOR UPDATE` after the queue lock): a run of that consumer alone over the item's range. Charged now -> booked and
  `RESOLVED`; charged by another run meanwhile -> `RESOLVED`; `NOT_CHARGED`, `NO_PLOTS` or no longer a consumer of the
  range -> `OBSOLETE`; still unknown, or no tariff -> open. Audit `NOT_CHARGED_RETRIED` (entity type `METER` about the
  consumer meter).
- **Settings of a consumer** (`CommonConsumerBillings`): `GET` gives `meterId`, `name`, `active`, `split` (absent: the
  cooperative's default), `effectiveSplit` and `servicePlotIds` (natural order); `PUT {split?, servicePlotIds}` sets
  them (absent `split` deletes the row). A meter that is not a common consumer meter (removed ones are accepted) is 404
  `NOT_FOUND`; `servicePlotIds[i]` `UNKNOWN_PLOT`, `DUPLICATE`, `PLOT_ARCHIVED`, `PLOT_COMMON_LAND` (400). One change of
  a consumer at a time (advisory lock); audit `COMMON_CONSUMER_BILLING_CHANGED` (entity type `METER`, label the meter
  name, `split` and `servicePlots` as plot numbers, only what changed). A change applies to the next run only.
- **Locks:** the cooperative's advisory lock of common consumer runs (`ledger.common-consumer-runs:{tenant}`) -> every
  electricity meter `FOR SHARE` -> the queue lock -> the charge types and tariffs `FOR SHARE` -> the billing month
  `FOR SHARE`: the order of the month run and the losses runs (no transaction takes both run locks).

## The "not charged" queue (tag `not-charged`, slice 3)

| Endpoint | Operation | Who |
|---|---|---|
| `GET /not-charged` | `listNotCharged` | `CHARGES_VIEW` |
| `POST /not-charged/{id}/retry` | `retryNotCharged` | `CHARGES_RUN` |

- **List** (`NotChargedQueue.list`, `NotChargedViews`): the open items, newest month (or range end) first, then the
  natural order of the plot numbers, then the charge types. Each with `id`, `kind`, `reason`, `plotId`, `plotNumber`,
  `chargeType` (code, stored name, `nameCustomized`), `meterId`, `month`, `from`, `to`, `tariffs[{id, name}]` (by
  name; the link to fix) and `createdAt`.
- **Retry** of an `INTERVAL` item: see Charges from readings. **Retry** of a `TARIFF_MONTH` item (`NotChargedRetries`,
  the path the month run uses for other months' items): the month's advisory lock, the queue lock, the item
  `FOR UPDATE`, the charge types and the tariffs `FOR SHARE`, then `TariffMonthPlanning` for the plot, type and month. A
  new charge is booked through
  `BillingMonths.openForBooking`: in the month, or, when it was closed meanwhile, in the earliest open month after it
  (the charge keeps its service period and `accrued_on`); the item is `RESOLVED` (also when the charge exists already),
  `OBSOLETE` when nothing is left to charge, and stays open (reason and tariffs refreshed) while the conflict remains.
  Answer `{resolved, item?}`; an item that is not open answers `{resolved: true}` and changes nothing; an unknown item
  or one of another cooperative is 404 `NOT_FOUND`. Common consumer items: see Common consumer runs. Audit
  `NOT_CHARGED_RETRIED` (entity type `CHARGE` about the plot; `kind`, `reason`, `chargeType`, `month`, `outcome`
  `RESOLVED`/`OBSOLETE`/`OPEN`).
- **Retry** of a `LOSSES_NODE` item (`LossesRuns.retry`, under the locks of a booking, the item `FOR UPDATE` after the
  queue lock): a run of that node alone over the item's range. Charged now -> the run is booked (audit
  `LOSSES_RUN_BOOKED`) and the item `RESOLVED`; charged by other runs meanwhile -> `RESOLVED` (for some of its days
  only: narrowed to the days left, as a booking does); negative, no longer a
  node of the range, or the losses mode not proportional -> `OBSOLETE`; still unknown -> open. Audit
  `NOT_CHARGED_RETRIED` with entity type `METER` about the node meter (label its name; `kind`, `reason`, `from`, `to`,
  `outcome`, `runId` when a run was booked).
- **Check list:** `notChargedCount` of a month counts its open items: those of the month itself and those without a
  month whose `to_date` falls in it.

## Charges from readings (slice 4)

Every change of the points of a plot meter charges the meter's intervals that are not charged yet (functional spec
2.4): `meters` publishes `PlotMeterPointsChanged(tenantId, meterId, plotId, resource, on, cause)` in the transaction of
an entered, approved, corrected or deleted reading and of a removal or replacement (plot meters only; a correction that
moves the date publishes one event per date; a meter import creates meters with their initial value only, which closes
no interval, so it publishes nothing). `ReadingCharges` listens with the ADR 0007 pattern (`@Async
@TransactionalEventListener`, `TenantContext.runAs`, then a `TransactionTemplate`) and calls `IntervalCharging`.

- **Intervals** (`IntervalCharging`, `meters.MeterNetwork.lockSharedPoints`): the points of the meter (initial value
  `INSTALL:{meter}`, approved readings by id, final value `REMOVAL:{meter}`; a reading on a boundary date takes its
  place) in date order; an interval is two neighbouring points. The listener handles the intervals that contain the
  event's date (the one ending there and the one starting there, or the one spanning it when no point is left on it).
  The initial value alone is no interval; a replacement charges the old meter's last interval (up to its final value)
  and later the new meter's first one.
- **The decision** (`ReadingChargePlanning`, pure): key `CONSUMPTION:{fromKey}:{toKey}`; a key that a charge has
  (reversed or not) is never booked again, so a redelivered event writes nothing and a reversed interval is not
  re-created (a correction is a new reading with a new key). `tariffs.TariffResolver` over the days of the interval for
  the charge type of the resource (`ELECTRICITY`, `WATER`): one tariff is priced; of two or more only the tariffs that
  would charge the interval by the meter take part (one of them is then the tariff, two or more `CONFLICTING_TARIFFS`,
  none nothing); an inactive or archived type charges and queues nothing (water until it is switched on); no tariff
  valid in the interval or one that misses days `NO_TARIFF_FOR_ALL_DAYS`, outside every scope `OUTSIDE_SCOPE`, excluded
  `EXCLUDED`. A tariff with no version of the meter's basis on any day of the interval (a fixed fee) is charged by the
  month run, not here.
- **The amount** (`ReadingChargeCalculator`, pure): the days of an interval run from the day after the previous point
  to the reading date, both included (`N` days); each `PER_KWH` (`PER_M3` for water) version gets
  `consumption x d / N` for its `d` applicable days, priced at its rate; one exact sum rounded once by
  `Money.ofQuotient`. The applicable days (`WaterDays.forInterval`) are every day for electricity and the days the
  plot is connected to water for a water meter: the m3 of the days not connected are dropped with their share, and an
  interval without a connected day is nothing before any tariff is looked at, so it is never queued (whatever the
  scopes and versions). `0.00` charges nothing. A connection entered after a reading was approved does not charge that
  reading again (its correction belongs to feature 012).
- **Fixed-percentage losses**: with `lossesMode = FIXED_PERCENT` and `lossesPercent > 0` an electricity interval also
  gets a `LOSSES` charge (charge type `ELECTRICITY`, key `LOSSES:{fromKey}:{toKey}`) of `percent / 100` of each part at
  the same rate, rounded once on its own; booked only together with the consumption charge.
- **The charge**: kind `REGULAR`, source `CONSUMPTION` or `LOSSES`, `tariff_id`, `meter_id`, service period = the first
  to the last applicable day, `accrued_on` = the reading date, booked by `BillingMonths.openForBooking` (the reading's
  month, or the earliest open month after it), no author (a retry: the person who retried), `calculation` `{amount,
  parts[{tariffVersionId, basis, frequency, quantity (kWh or m3, three decimals for display), unit KWH|M3, rate, days,
  from, to}], readings[{meterId, takenOn, value}] (the two points), lossesPercent and consumption (losses only)}`, one
  `charge_part` per version with the exact quantity as `units_num / units_den`, and one `meter_value_use` row per point.
  `ChargesBooked` for the plot.
- **The queue**: a failure opens (or refreshes: reason, tariffs, the interval's start) the item
  `INTERVAL:{meter}:{toKey}` (`plot_id`, `charge_type_id`, `meter_id`, `from_date` = the first day, `to_date` = the
  reading date, no month, `params` `{tariffIds, fromKey, toKey}`); a charge (now or before) closes it `RESOLVED`,
  nothing to charge `OBSOLETE`; an open item of the meter whose interval no longer exists (a reading inserted or
  deleted before its point) becomes `OBSOLETE`. Business failures never throw.
- **Retries**: `POST /not-charged/{id}/retry` of an `INTERVAL` item charges the interval that ends at the item's point
  (`OBSOLETE` when the point is gone), audit `NOT_CHARGED_RETRIED` with `kind`, `reason`, `chargeType`, `from`, `to`,
  `outcome`. A booked month run retries every open `INTERVAL` item of the cooperative, whatever its month, before it
  locks its tariffs; those charges are not part of the run's preview, digest or totals.
- **Locks**: the meter `FOR SHARE` with its points (a reading write holds it `FOR UPDATE`, so the two run one after the
  other) -> a transaction advisory lock per meter (`ledger.meter-charges:{tenant}:{meter}`: ledger transactions about one
  meter run one after another, so a key is checked and written once and an item opened once) -> the charge types and
  every tariff `FOR SHARE` -> the billing month `FOR SHARE`. The month run takes the meters of its retries before its
  tariffs.

## Readings in use

`LedgerReadingUsage` implements `meters.ReadingUsage`: a charge reads its meter on the closed range of days from its
first to its last `meter_value_use` row (an interval charge: from its previous point to its reading); the readings of a
span are used when such a range of a charge that is not reversed meets the span. The meters module asks about the days
a change on a date reaches, from the day after the previous point to the day before the next one (or the date itself),
so a correction or deletion of a charged reading and a reading inside a charged interval are 409 `READING_IN_USE`, a
new reading after a charged interval is accepted, and after the reversal of the charge the reading can change again.
A booked losses run reads every meter of its charged nodes on the closed range of its `meter_value_use` rows (with
`run_id`), and a booked common consumer run the meters of the consumers it charged; the rows of a reversed run no
longer count. It replaced the stub `meters/internal/NoChargesYetUsage`.

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

Entity type `LOSSES_RUN` (`LedgerAuditPublisher.publishLossesRun`), about the run, label its range `from/to`:

- `LOSSES_RUN_BOOKED`: `from`, `to`, `billingMonth`, `nodes` (charged), `count` (charges), `total`.
- `LOSSES_RUN_REVERSED`: `from`, `to`, `count` (reversals), `total` (their sum, negative), `reason`.

Entity type `COMMON_CONSUMER_RUN` (`LedgerAuditPublisher.publishCommonConsumerRun`), about the run, label `from/to`:

- `COMMON_CONSUMER_RUN_BOOKED`: `from`, `to`, `billingMonth`, `consumers` (charged), `count` (charges), `total`.
- `COMMON_CONSUMER_RUN_REVERSED`: `from`, `to`, `count` (reversals), `total` (negative), `reason`.

Entity type `METER` about a common consumer meter (label its name):

- `COMMON_CONSUMER_BILLING_CHANGED`: `split` (the own split; absent value = the cooperative's default) and
  `servicePlots` (plot numbers in natural order, joined by ", "), each only when it changed.

Entity type `CHARGE` about the plot, as above:

- `NOT_CHARGED_RETRIED`: `kind`, `reason`, `chargeType`, `month` (a month item) or `from` and `to` (a reading
  interval), `outcome`. A losses node item is an entry of entity type `METER` about the node meter (label its name)
  with `kind`, `reason`, `from`, `to`, `outcome` and `runId` when a run was booked; a common consumer item likewise,
  about the consumer meter.

The charges themselves are the ledger and are not repeated in the log. Reasons are user content: never logged.

Every action is a constant of `LedgerAuditPublisher.Action` and must also be a value of `AuditAction` in
`api/openapi.yaml`: the audit listing maps stored entries with `AuditAction.fromValue`, so a missing value breaks it
(guarded by `LedgerAuditActionsTests`).

## Dependencies

`tariffs` (the read facade `ChargeTypeCatalog`: `find`, `findAndLockShared`, `all`; `TariffBook`: `snapshot`,
`tariffIds`, `lockShared`; the pure `TariffResolver`, `ChargeCalculator.breakdown` and `DaySet`; the port
`TariffVersionUsage`, implemented here), `plots` (`PlotCatalog`: `find`, `findAll`, `findAndLockShared`,
`inNaturalOrder`, `findChargeable`; the SPI `PlotChangeGuard`; the debt cache writer `PlotDebtCache`),
`meters` (`MeterNetwork.staleMeters`, `lockSharedPoints`, `network` and `lockSharedNetwork`, the event
`PlotMeterPointsChanged`, `ConsumptionCalculator`, `Quantity`, `MeterValue`, `WaterConnections.connectedDays` and
`connectedToSource`, `MeterCatalog.find`; the port `ReadingUsage`, implemented here), `settings`
(`CooperativeSettings.lossesMode`, `lossesPercent`, `commonConsumerDefaultSplit`; `CommonConsumerSplit`), `money` (`Money`), `identity` (`CurrentUser`, `AccessProfile`, `Permission`,
`OwnedPlotsSource`), `audit` (events, `ActorNames`), `problem`, `tenancy` (`TenantContext`). Nothing depends on
`ledger`.

## Permissions

`CHARGES_VIEW` (Chairman, Treasurer): every plot's account, the months, the queue, the losses runs and the common
consumer runs. `CHARGES_RUN` (Chairman): opening balances, the month run (and its preview), retries, losses runs and
common consumer runs (and their previews). `CHARGES_REVERSE` (Chairman): reversing a charge, a losses run or a common
consumer run. `SETTINGS_MANAGE`: reading and changing the split and service users of a common consumer. `PERIODS_CLOSE` (Chairman): closing a month. Defined in
`identity.Permission`, seeded by `seed_base_roles` (V13; existing cooperatives by targeted inserts).

## Tests

`BillingCalendarTests`, `DebtCalculatorTests` (pure); `MonthRunTests` (the month-run rows of §3: preview, booking,
rerun, a plot added later, 0.00, a reversed charge, one-time fees, scopes, inactive types, tariffs by readings,
conflicts to the queue naming both tariffs, only tariffs above 0.00 in a conflict (two one-time fees due in different
months), no conflict for a plot charged before a second tariff joined, a stale digest, closed and future months,
permissions, audit, plot debt), `NotChargedQueueTests` (list order, retry unfixed, fixed, nothing left, a closed month,
auto-closing by the run, the next month's run previewing and booking a fixed earlier item with its own service period
or keeping it open with the run's tariffs, 404, permissions, audit), `ReadingChargeCalculatorTests` and
`ReadingChargePlanningTests` (pure: the reading rows of §3, keys, the conflict rule, the queue reasons, losses),
`ReadingChargesTests` (entry, member approval, replacement, removal, a closed month, no tariff -> queue -> retry and
month run, a deletion, conflicts, water inactive, fixed losses, a redelivered event), `ReadingUsageTests` (409 while
charged, a reading inside a charged interval, a new reading after one, accepted after the reversal),
`TariffVersionUsageTests` (409 after the run, accepted after the reversal), `LossesCalculatorTests` (pure: the losses
rows of §3 - quarter, substation, negative -50, unknown skipping above - shares, a plot moved between quarters,
calculated values, the values read, the price once per plot, a rate change, overlaps, plots without a tariff, the
digest, losses that are 0.000 kWh at three decimals), `LossesRunsTests` (preview, booking with charges, parts, value
uses and audit, a stale digest, the overlap refusal, the reversal of every charge with the run's reason, a closed
month, a skipped node charged later by retries, an item narrowed by a run or a retry over some of its days and resolved
by a run over the rest, losses booked as 0.000, calculated marks, the readings a run uses, the mode, validation, node
selection, permissions), `LossesRunTenantIsolationTests` (V17), `CommonConsumerSplitCalculatorTests` and
`CommonConsumerPlanningTests` (pure: the common consumer rows of §3 - equal 54.00, connected plots 86.40, plot-days
24/24/12 kWh, users 43.20, not charged - the price over a plot's own days with a rate change, rounding once, plots that
cannot be priced, outcomes, no tariff, overlaps, the values read, the digest, the calculation), `CommonConsumerRunsTests`
(the modes, booking with charges, parts, value uses, account and audit, no tariff, overlap, stale digest, reversal, a
closed month, an unknown consumer queued and retried, the readings a run uses, validation, consumer selection, the
settings of a consumer with validation and audit, permissions), `CommonConsumerTenantIsolationTests` (V18), the water
rows of `MonthRunTests` (25.00 for
a connected plot, nothing and nothing queued for one that is not, 12.90 from the 16th, a conflict only for a connected
plot) and of `ReadingChargesTests` (a plot not connected for the whole interval: nothing and nothing queued; the
connected days only),
`TariffConflictWarningTests` (the tariffs page), `NotChargedTenantIsolationTests` (V15) - these run in a cooperative of
their own (`FreshCooperativeSupport`); `BillingMonthsTests` (the list and totals, the month page and its
check list, closing in order, a closed month that gets nothing more, late bookings, future months, permissions, audit,
the `billing_month_guard` trigger); `PlotStatusRefreshTests`; `LedgerAccountTests` (the account, debts per type, the reversal
rules of §3, opening balances, booking after a closed month, the plot guard, allowed and denied per endpoint for the
chairman, the treasurer, custom roles, a member for their own and another plot, the other cooperative and the platform
operator); `LedgerAuditTests` (entries and `ChargesBooked`); `LedgerTenantIsolationTests` and `LedgerAppendOnlyTests`
(the tables); `LedgerMigrationTests` (V13 on existing cooperatives); `ChargesBookedTests`. Test helpers clear ledger rows
and billing months as `nadel_owner` with the maintenance setting (`LedgerTestSupport`).
