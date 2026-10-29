# ledger

The ledger of the cooperative (feature 011): the **charges** of plots, the **billing months** they are booked in,
**reversals**, **opening balances**, the **account** of a plot with the debt per charge type, the **month run** of
recurring tariffs, the **charges from meter readings**, the **proportional losses runs**, the **common consumer runs**
and the **"not charged" queue**. Spec 011 is in code (slice 2: the billing months with their totals and check list,
closing in order, and the plot status refresh; slice 3: the month run and the queue; slice 4: a charge per interval of
a plot meter and fixed-percentage losses; slice 5: water tariffs charge only the days a plot is connected to water;
slice 6: proportional losses runs; slice 7: common consumer runs and the split of each consumer; slice 8: the opening
balances sheet of the import; slice 9: the demo ledger). Feature 012 adds the **recalculation** of past charges:
**adjustments**, the recalculation planner and booking, the cause-driven corrections of tariff versions and readings
and the recalculation records (see Recalculations). The money rules are `docs/domain/money-rules.md` sections 2, 3,
4.1, 5, 6.5 to 6.7, 9 and 11.

Tables (V13, all under RLS `tenant_isolation`, an index leading with `tenant_id`, composite foreign keys on
`(tenant_id, id)`):

| Table | What it holds |
|---|---|
| `billing_month` | An accounting period: `month` (its first day), `status` `OPEN` or `CLOSED`, `run_at`, `closed_at`, `closed_by`. Created on demand, `OPEN` by default; never deleted (`DELETE`/`TRUNCATE` revoked). The V14 trigger `billing_month_guard` refuses any change of a closed month (so no reopening), a change of `month`, and every `DELETE`/`TRUNCATE`, to everyone but `nadel_owner` with the maintenance setting |
| `charge` | The ledger. `kind` `REGULAR`/`REVERSAL`/`OPENING`/`ADJUSTMENT` (V19), `source` `TARIFF`/`CONSUMPTION`/`LOSSES`/`COMMON_CONSUMER`/`OPENING`/`MANUAL_REVERSAL`/`RECALCULATION` (V19), `amount numeric(14,2)`, service period `service_from..service_to`, `accrued_on` (the age of the debt), `billing_month_id`, `calculation jsonb` (codes and numbers only), `source_key` (idempotency of later runs), `reverses_charge_id` (only on a reversal), `adjusts_charge_id` (only on an adjustment: the `REGULAR` charge it corrects, V19), `recalculation_id` (the recalculation that booked an adjustment or a reversal of source `RECALCULATION`, V19), `reason` (required on a reversal, optional on other kinds, for example an opening balance), `created_by`, `created_at` |
| `charge_part` | The tariff versions behind a charge (days, an exact quantity `units_num/units_den`, the rate); written by the month run (one row per version) |
| `meter_value_use` | The meter values a charge or run read: one row per point date (an interval charge: its two points). Not a ledger table. Rows stay after a reversal; `LedgerReadingUsage` leaves out the rows of reversed charges |
| `losses_run` (V17) | A proportional losses run: `from_date`, `to_date`, `status` `BOOKED`/`REVERSED`, `billing_month_id` (its charges' month), `total`, `created_by`, `created_at`, `reversed_at`, `reversed_by`, `reverse_reason`. Its charges are `charge` rows with `run_id` and source `LOSSES` |
| `losses_run_node` (V17) | A node of a run as booked: `node_meter_id` (the node's chain, its first meter), `node_kind`, `node_name`, the run's range and status repeated (`from_date`, `to_date`, `run_status`), `position`, `outcome` `CHARGED`/`NEGATIVE`/`UNKNOWN`/`SKIPPED_ABOVE`, `consumption`, `children`, `losses` (kWh, three decimals for display), `pct`, `calculated`, `amount`, `warning jsonb`, `plots jsonb` (codes, ids and numbers only). The exclusion constraint `losses_run_node_no_overlap_ex` keeps two booked runs from charging a node for the same day (only `BOOKED` and `CHARGED` rows) |
| `common_consumer_run` (V18) | A common consumer run: the columns of `losses_run`. Its charges are `charge` rows with `run_id` and source `COMMON_CONSUMER` |
| `common_consumer_run_line` (V18) | A common consumer of a run as booked: `consumer_meter_id` (the consumer's chain, its first meter; no foreign key, so a meter entered by mistake can still be deleted after a run listed it as unknown), `consumer_name`, the run's range and status repeated, `position`, `split`, `outcome` `CHARGED`/`NOT_CHARGED`/`NO_PLOTS`/`UNKNOWN`, `consumption` (kWh, three decimals for display), `calculated`, `plot_days`, `amount`, `warning jsonb`, `plots jsonb` (`plotId`, `days`, `kwh`, `amount?`, `reason?`). The exclusion constraint `common_consumer_run_line_no_overlap_ex` keeps two booked runs from charging a consumer for the same day (only `BOOKED` and `CHARGED` rows). Both run tables change only by the reversal (`run_reversal_guard`, V17) |
| `common_consumer_billing` (V18) | Settings: the split of one common consumer meter (`CONNECTED_PLOTS`/`EQUAL_PER_PLOT`/`SERVICE_USERS`/`NOT_CHARGED`); no row = the cooperative's `common_consumer_default_split`. Changed and deleted by the application; goes with its meter (`ON DELETE CASCADE`) |
| `common_consumer_user` (V18) | Settings: the plots marked as using a common consumer (`SERVICE_USERS`); PK `(tenant_id, consumer_meter_id, plot_id)`; goes with its meter and its plot |
| `recalculation` (V19) | A booked recalculation (feature 012): `cause` `MANUAL`/`TARIFF_VERSION`/`READING`, `cause_ref_id` (no foreign key), `scope jsonb` (ids and codes under the keys of the contract's `RecalculationScope`, plus `causeType`), `reason` (1-500, user content), `total`, `line_count`, `plot_count`, `digest`, `created_by`, `created_at`. Append-only like `charge` |
| `recalculation_line` (V19) | A line of a recalculation: `plot_id`, `charge_type_id`, the service period, `original_charge_id`, `booked_amount`, `adjusted_so_far`, `expected_amount`, `difference`, `action` `REVERSE_AND_RECHARGE`/`ADJUST`/`NEW`/`REVERSE`, `reasons text[]` (codes of `RecalculationChangeReason`), `run_id`, the charges it booked (`reversal_charge_id`, `new_charge_id`, `adjustment_charge_id`), `position`. Append-only |
| `payment` (V27) | Money received for one plot (feature 015): `kind` `PAYMENT` (amount > 0) or `PAYMENT_REVERSAL` (the negated amount, `reverses_payment_id`, `reason` 1-500), `source` `CASH`/`BANK`, `received_on`, `billing_month_id`, `group_id` (the parts of one split payment; a single payment is its own group), `receipt_number` (exactly the cash payments; every part of a cash group carries the group's number, the gist exclusion `payment_receipt_group_excl` keeps a number to one group), `bank_reference` (1-64, bank only), `payer_member_id`, `payer_note_enc` (the payer note sealed at `FieldRef("payment", "payer_note", id)`, ADR 0016), `created_by`, `created_at`. Reversed at most once (`payment_reversed_once_uq`); `payment_target_guard` keeps a reversal on a payment of the same plot with the negated amount. Append-only like `charge` |
| `allocation` (V27) | A part of a payment placed on one charge of the same plot (`amount` > 0, `origin` `ENTRY`/`REALLOCATION`/`PREPAYMENT_MANUAL`/`PREPAYMENT_AUTO`) or a release (`amount` < 0, `releases_allocation_id`, `release_cause`); `created_by` null = the system. Composite keys `(tenant_id, payment_id, plot_id)` and `(tenant_id, charge_id, plot_id)` make "same plot and cooperative" structural; `allocation_guard` keeps a positive row on a `REGULAR`/`OPENING` charge that is not reversed and a payment that is not reversed, and a release within what is left of its allocation. Deferred backstops check at commit that a payment's and a charge's net allocations never exceed them. Append-only |
| `receipt_counter` (V27) | The last cash receipt number per cooperative (`tenant_id` PK, `last_number`); a trigger allows only `+1`; `DELETE`/`TRUNCATE` revoked |
| `not_charged_item` (V15) | The "not charged" queue: `item_key`, `kind` `TARIFF_MONTH`/`INTERVAL`/`LOSSES_NODE`/`COMMON_CONSUMER`, `plot_id`, `charge_type_id`, `meter_id`, `month`, `from_date`, `to_date`, `reason`, `params jsonb` (codes and ids only, `tariffIds`), `status` `OPEN`/`RESOLVED`/`OBSOLETE`, `resolved_at`. Not a ledger table: the application updates the status and, while open, the reason and params; `DELETE`/`TRUNCATE` revoked. One open item per key (`not_charged_item_open_key_uq`); an item goes with its plot or meter (`ON DELETE CASCADE`) |

**Append-only (ADR 0006).** `nadel_app` has no `UPDATE`, `DELETE` or `TRUNCATE` on `charge` and `charge_part`, and a
`BEFORE UPDATE OR DELETE` row trigger plus a `BEFORE TRUNCATE` trigger refuse them to everyone else too. The only way
past the trigger is `nadel_owner` with `SET LOCAL app.ledger_maintenance = 'on'`, for demo data and test helpers;
`nadel_app` is refused even with the setting. In Java the entity `ChargeRecord` is `@Immutable` and only ever persisted.
CHECK constraints keep a reversal negative with a reason and a pointer, an adjustment non-zero (either sign) with source
`RECALCULATION`, a reason, its target and its recalculation, every other row positive (a reason optional); the partial
unique index `charge_reversed_once_uq` lets a charge be reversed once and `charge_adjustment_once_uq` adjusted once per
recalculation. The trigger `charge_target_guard` (V19) lets an adjustment target only a `REGULAR` charge of its
cooperative that is not reversed, and a reversal never target an adjustment or a reversal. `recalculation` and
`recalculation_line` are append-only the same way.

## Public API

| Type | Use |
|---|---|
| `ChargesBooked(tenantId, plotIds)` | Published inside the transaction of every booking or reversal (a reversal, an opening balance, the month run, a retry and the charges of readings, at most 500 plots per event; every later booking path must publish it too). `PlotStatusRefresher` recomputes the debt of those plots from the committed ledger. Ids only, no personal data; the set is never empty |
| `OpeningBalanceImport` (with `Row`, `Item`, `Plan`, `Problem`, `Action`) | The "Opening balances" sheet of the Excel import (slice 8), `plan`/`lock`/`apply` like `meters.MeterImport`; implemented by `internal.OpeningBalanceImportService`, see Opening balances from the import |
| `PaymentsChanged(tenantId, plotIds)` | Published inside the transaction of every change of payments or allocations of plots (feature 015: a payment entered, one event with every plot of a split; later slices a reversal, a reallocation, a prepayment allocation by hand or automatically), never empty, at most 500 plots. `PlotStatusRefresher` recomputes the debt and prepayment of those plots. Ids only |
| `PaymentRecorded(tenantId, paymentIds)` | Published inside the transaction of every payment entry (feature 017 §2.4), next to `PaymentsChanged`: one event per save with every payment written (one for a plot, one per part above zero for a split, each part a payment of its own plot), never empty. `notifications.PaymentMessages` plans the payment messages to the plots' owners. Ids only: no amounts, names or texts in the outbox |
| `PaymentReversed(tenantId, paymentIds)` | Published inside the transaction of a payment reversal (feature 017 §2.4), next to `PaymentsChanged`, with the id of the reversed payment (kind `PAYMENT`, not its reversal row), never empty. `notifications.PaymentMessages` plans the reversal messages. Ids only |
| `PaymentSource` (`CASH`, `BANK`) | Where the money of a payment came from (feature 015; column `payment.source`, V27); the bank statement import is a later feature (after 016-018) |
| `PaymentNotes` | The payer notes of payments for the payments report (feature 015 §6), implemented by `internal.PaymentNotesReader`: `@PreAuthorize(MEMBERS_VIEW)`, `Propagation.MANDATORY`, opened with `PaymentNoteCipher`. The one documented way personal data leaves the ledger for the reports |
| `LedgerReadModel` (with `MonthTypeTotals`, `MonthGrouping`, `MonthStatus`, `PlotDebts`, `PaymentRow`, `PaymentFilter`, `CorrectionRow`, `CorrectionKind`, `CorrectionSource`, `CorrectionCause`, `ChargedRow`, `ChargedRowKind`, `ChargedSource` (with `TARIFF` for the water rows), `ChargedPart`, `LossesRunNodeRow`, `LossesRunNodeOutcome`, `CommonConsumerRunLineRow`, `CommonConsumerRunLineOutcome`, `RunStatus`, `CashReceiptData`, `InvoiceLedger`) | The read facade of the reports (feature 013), of the documents (feature 016: `cashReceipt`, `invoiceLedgers`, `firstBillingMonth`) and of the payment messages (feature 017: `paymentsByIds`, `debtsAsOf`), implemented by `internal.LedgerReportQueries`; see The read model of the reports |

Everything else is in `internal`.

## Rules (pure, unit-tested)

- `MonthMovements.of(month, asOf, closedMonths, charges, allocations, payments)` (feature 016, money rules 13): the
  booking-month rule of an invoice. A charge and a payment count in their own billing month; an allocation or a
  release in `GREATEST(payment month, charge month, openMonthAt(created_at))` (`countedIn`), `openMonthAt(t)` being the
  month after the last month closed at or before `t` (none: no bound); rows created after `asOf` never count. Per
  charge type start (before the month), charged (in it), paid (allocations counted in it) and end; the prepayment at
  the end (payments booked by then − allocations counted by then, never below 0.00); the later payments (booked after,
  created by `asOf`, not reversed by then, by day received). A closed month never changes. Tests:
  `MonthMovementsTests` (worked examples 1-5 of feature 016, the open month, rows after `asOf`, a reversed later
  payment).

- `BillingCalendar.bookingMonth(date, months, today)`: the month of `date`, never after the current month. Open (or not
  existing yet after the last closed month) -> that month. Closed (months close in order, so a missing month before the
  last closed one counts as closed) -> the earliest open month after it, which is always the month right after the last
  closed one (opened when it has no row; a later month that has a row is never preferred). It is after the current
  month only when the current month itself is closed. The charge keeps its own service period and `accrued_on`. Tests:
  `BillingCalendarTests`.
- `DebtCalculator.of(lines)`: debt per charge type = the sum of the plot's charges of the type, reversals negative,
  adjustments with their sign; total = their sum; the oldest unpaid accrual = the oldest `accrued_on` among the charges
  that are neither a reversal nor an adjustment, were not reversed, and whose effective amount (the charge plus its
  adjustments) is above zero; prepayment zero. This is the rule for charges only; the account uses it until it loads
  payments (feature 015 slice 6); the plot status (`PlotStatusRefresher`) uses the rule after payments below. An adjustment carries the debt
  age of its charge and never counts on its own. Exact `Money` addition, no rounding. Tests: `DebtCalculatorTests`.
- `DebtCalculator.of(charges, allocations, payments)` (feature 015 §2.5): debt per charge type = Σ charges of the type
  − Σ net allocations to them (releases negative); prepayment = Σ payments (reversals negative) − Σ net allocations,
  never below zero; the oldest unpaid accrual = the oldest `accrued_on` among the `REGULAR`/`OPENING` charges that were
  not reversed and still have an open amount above zero. A plot with debt 0.00 and a prepayment is GREEN. Tests:
  `DebtCalculatorTests` (examples 4, 5, 8).
- Payments, pure (feature 015 §2.2-§2.6, technical considerations 2.3), fed with the ledger rows of a plot
  (`OpenAmounts.ChargeEntry`, `AllocationEntry`, `PaymentEntry`, which `OpenAmounts.entry(record)` builds from the
  entities):
  - `OpenAmounts.of(...)` → `PlotLedgerState` (`ChargeState`, `AllocationState`, `PaymentState`): only `REGULAR` and
    `OPENING` charges receive money; effective = the charge plus its adjustments, `0.00` once reversed; open =
    effective − net allocations; what is left of an allocation = it plus its releases; a payment is reversed when a
    `PAYMENT_REVERSAL` points at it. The charge type position is the index in `ChargeTypeCatalog.all()`, unknown last.
  - `AllocationPlanner`: `ORDER` = `accrued_on`, then the charge type position, then the service start (fallback
    `accrued_on`), then `created_at`, then the id as text (PostgreSQL's `uuid` order); `propose(amount, charges)` gives
    each open charge `min(remaining, open)`, the rest is the remainder; `validate` returns `ALLOCATION_EXCEEDS_PAYMENT`
    (all lines) and per line `DUPLICATE`, `AMOUNT_NOT_POSITIVE`, `NOT_FOUND` (not an allocatable charge of the plot),
    `ALLOCATION_EXCEEDS_CHARGE`; `completeWithRemainder(lines, amount, charges)` (owner decision of 28.10.2026) places
    what valid lines leave of the amount on the charges still open after them, in `ORDER`, each up to what the lines
    left open on it, and leaves what does not fit (no open charge left); `pairWithPayments` names prepayment lines after
    the payments with money left, oldest first (`received_on`, `created_at`, id). Tests: `AllocationPlannerTests`
    (examples 1, 2, 3, 10, 11, 18, 19, 20, the remainder, tiebreaks).
  - `LinePlacement` (pure value): the lines of one plot for an amount (the proposal, or the caller's lines with their
    `validate` problems) and, only when they are valid and automatic allocation is on, the automatic lines
    (`completed`); `allocated` (the lines above zero plus the automatic ones), `automaticTotal`; `requireValid(prefix)`
    throws the 400 a save answers (`VALIDATION_FAILED` per line first, then `ALLOCATION_EXCEEDS_PAYMENT`, then
    `ALLOCATION_EXCEEDS_CHARGE`). Shared by entry, split, reallocation and prepayment allocation.
  - `PaymentSplitter.split(amount, plotDebts)`: amount ≥ total debt → each plot its debt and the excess to the first
    plot (also when they owe nothing); otherwise `Money.allocate(debts)` (kopecks down, leftover to the largest
    remainders, ties to the first plot; the rounding lives in `Money`, ADR 0013). Tests: `PaymentSplitterTests`
    (examples 12, 13, 14), `MoneyAllocateTests`.
  - `ReleasePlanner`: `forCharges(state)` releases everything on a reversed charge (`CHARGE_REVERSED`) and the excess
    over a lowered charge's effective amount from its latest allocations first (`CHARGE_ADJUSTED`);
    `forPayment(payment, allocations, cause)` releases every live allocation of a reversed (`PAYMENT_REVERSED`) or
    reallocated (`REALLOCATED`) payment. Tests: `ReleasePlannerTests` (examples 6, 7, 9).
  - `PaymentDigest` (SHA-256 hex): `entry` (amount, parts in order, each plot's open charges in planner order as
    `chargeId:open`, then the lines a save writes: the proposal's or the caller's lines in their order, then the
    automatic ones), `reallocation` (payment, live allocations `id:remaining`, open charges as if released, the lines a
    save writes), `prepayment` (plot, prepayment, open charges). A save with other lines than its preview, or after the
    setting changed what is placed automatically, is therefore 409 `PREVIEW_OUTDATED`. Tests: `PaymentDigestTests`.
  - `PaymentRules`: amount above zero within `numeric(14,2)`, a date not after today in Kyiv (`Clock`), payer note
    ≤ 200 characters, bank reference ≤ 64 characters and only on a bank payment, reversal reason 1–500 characters
    (field codes `AMOUNT_NOT_POSITIVE`, `INVALID`, `DATE_IN_FUTURE`, `TOO_LONG`, `REQUIRED`). Tests: `PaymentRulesTests`.
- `BillingMonths.openForBooking(tenant, date)` (inside the caller's transaction, `MANDATORY`; every booking path goes
  through it): asks the calendar with the months' current statuses (scalars, never stale managed entities), inserts the
  month `ON CONFLICT DO NOTHING` when missing, locks it `FOR SHARE` and reads its status as a scalar under the lock, so a
  closing (`FOR UPDATE`) waits for the booking or the booking waits for the closing; a month found closed under the
  lock makes it ask the calendar again.
- `ChargeIdentity.of(...)` (feature 012): what a charge charges, whatever its key. A month-run charge
  `T:{tariff}:{plot}:{YYYY-MM}` (from the base source key, any `#…` suffix stripped); an interval charge
  `I:{source}:{meter}:{fromDate}:{toDate}` (consumption or fixed losses, the dates of the two points from the stored
  calculation, not the source key: a corrected reading has a new id, but the interval is the same while its dates
  stay); a losses run line `R:{run}:{plot}`, a common consumer run line `R:{run}:{consumer}:{plot}`. Only a `REGULAR`
  charge has one; reversals and adjustments join the chain of their charge, opening balances are never in scope.
  Tests: `ChargeIdentityTests`.
- `BookedChain.of(rows)`: the rows of one identity (its `REGULAR` charges, their reversals and adjustments). Effective
  amount = the exact sum of the chain; the live charge = the `REGULAR` charge that is not reversed (of two, the one not
  adjusted to `0.00`); a chain whose charge was reversed by hand (`MANUAL_REVERSAL`) and not booked again is
  **frozen** and left alone.
- `RecalculationPlanner.plan(input)`: expected (from the 011 planners, `ExpectedCharges`) against the chains, per
  identity: frozen -> nothing; a live charge in an open month that differs -> `REVERSE_AND_RECHARGE` (expected absent or
  not charged -> `REVERSE`); a live charge in a closed month -> `ADJUST` for `expected - effective` in
  `BillingCalendar.bookingMonth(original.accruedOn)`; no live charge and an expected one -> `NEW` (its own month if
  open, else the earliest open month); equal -> nothing; a charge that belongs to another plot or type now -> it goes
  and the expected one is new. A run follows its own billing month (`runMonths`): open and any line differs -> every
  live line reversed and the run booked again (`REBOOK`; `REVERSE` when nothing is left to charge), closed -> the
  identity rules per line (`PER_LINE`). The queue: an open item whose identity is charged -> `RESOLVED`, one that cannot
  be charged -> `OPEN` with the reason, one with nothing left -> `OBSOLETE`. The only arithmetic is `expected -
  effective`, two amounts rounded already. `ChangeReasons` names why a line changed (compares the booked and expected
  calculations: version or rate, days, area, readings, water days, scope, run; else the cause). `RecalculationDigest`:
  SHA-256 over the normalised cause, the scope and the sorted lines (identities, never generated ids), runs and queue
  outcomes. Tests: `RecalculationPlannerTests` (every row of the requirement §3), `RecalculationDigestTests`.

## Endpoints (tag `charges`)

| Endpoint | Operation | Who |
|---|---|---|
| `GET /plots/{id}/account?from&to&kind` | `getPlotAccount` | `CHARGES_VIEW` for any plot, otherwise a current owner of the plot; anyone else, an unknown plot and one of another cooperative the same 404 `PLOT_NOT_FOUND`; the platform operator 403. Payments on it: `PAYMENTS_VIEW` or a current owner |
| `POST /charges/{id}/reverse` | `reverseCharge` | `CHARGES_REVERSE` |
| `POST /plots/{id}/opening-balances` | `enterOpeningBalance` | `CHARGES_RUN` |
| `GET /recalculations?page&size` | `listRecalculations` (tag `recalculations`) | `CHARGES_VIEW` |
| `GET /recalculations/{id}` | `getRecalculation` | `CHARGES_VIEW`; unknown or another cooperative's 404 `NOT_FOUND` |
| `POST /recalculations?preview` | `runRecalculation` | `RECALCULATION_RUN` (preview and booking, see Recalculations) |

- **Account** (`AccountQueries`, one read-only transaction; feature 015 §2.9, §2.10): the plot's charges, allocations
  and payments are read once each, and `DebtCalculator.of(charges, allocations, payments)` / `OpenAmounts` give the
  debt after payments. `debts[]` lists every charge type the plot has a charge of, in the order of the charge types,
  with its debt after payments (its charges minus what payments pay on them; also `0.00` after a reversal or when paid);
  `total` their sum; `prepayment` the money on no charge (`PlotLedgerState.prepayment`, never below `0.00`);
  `prepaymentAllocatable` whether "Розподілити аванс" is offered to the viewer (`LedgerAccess.prepaymentAllocatable`:
  `PAYMENTS_ENTER`, the setting `autoAllocatePrepayment` off, a prepayment above `0.00` and an open charge);
  `charges[]` newest first by `accrued_on`, then booking time; `payments[]` (the payments and their reversals) newest
  first by `received_on`, then entry time; `prepaymentAllocations[]` newest first. The client interleaves the lists by
  date (`accruedOn`, `receivedOn`, `allocatedOn`). `from`/`to` (inclusive) limit the lists (charges on `accrued_on`,
  payments on `received_on`, placements on their day in Europe/Kyiv), never the debts or the prepayment; `from` after
  `to` is 400 `INVALID` on `from`. `kind` (`PlotAccountKind`): a charge kind lists only those charges (no payments, no
  placements); `PAYMENTS` ("Лише платежі") only the payments and their reversals (no charges, no placements); neither
  changes the debts. Each charge carries its billing month (`YYYY-MM`) and whether it is closed,
  `reverses`/`reversedBy`, `reason`, the stored `calculation` (a charge stored without one shows its amount with no
  parts), `createdAt`, `createdByName` (the author's current name through `audit.ActorNames`, only for `CHARGES_VIEW`)
  and `reversible` (the viewer has `CHARGES_REVERSE`, the charge is neither a reversal nor an adjustment, not reversed
  and its month is open). `kind` limits the list to one kind (the web's "adjustments only"), never the debts. Links
  within the plot's ledger: an adjustment has `adjusts {chargeId, billingMonth, serviceFrom, serviceTo}` (the charge it
  corrects, also when that charge is outside the range), an adjusted charge `adjustedBy[]` (its adjustments, oldest
  first; absent when none), and a row of a recalculation `recalculationId`, only for a viewer with `CHARGES_VIEW` (a
  member sees their adjustments without it). A regular charge or an opening balance also carries `paid` (net
  allocations) and `open` (its effective amount, `0.00` once reversed, minus `paid`); reversals and adjustments carry
  neither (they change the open amount of their charge).
- **Payments on the account**: each `AccountPayment` has `kind` (`PAYMENT`/`PAYMENT_REVERSAL`), `groupId`, `source`,
  `amount` (negative for a reversal), `receivedOn`, `billingMonth`, `receiptNumber` (cash), `bankReference`,
  `reversed`/`reversedBy` (a payment), `reverses`/`reason` (a reversal), `createdAt` and `allocations[]`: what the
  payment paid, every allocation and release of it oldest first as on the card (`PaymentAllocation`: charge, charge
  type, service period, amount, `origin` or `releaseCause`, `system`), the placements of its money from the prepayment
  included; a reversal has none. `prepaymentAllocations[]` ("Зараховано з авансу") are the allocation rows of origin
  `PREPAYMENT_AUTO` or `PREPAYMENT_MANUAL` grouped by their time, origin and author (one run of the allocator or one
  "Розподілити аванс"), with `allocatedOn` (the day in Europe/Kyiv), `createdAt`, `origin`, `system` (no author),
  `amount` (their sum) and `lines` in the order of their charges. The same rows are listed among the allocations of
  their payments: the placements are the dated view of them, so the account shows when the prepayment was placed as
  well as what each payment paid. Who sees what: the lists are filled for a viewer with `PAYMENTS_VIEW` and for a
  current owner of the plot (`LedgerAccess.maySeePayments`), and empty for anyone else who may see the account (a
  custom role with `CHARGES_VIEW` only still sees the debts after payments, `paid`, `open` and `prepayment`);
  `payerNote` only with `PAYMENTS_VIEW` and `MEMBERS_VIEW` (opened by `PaymentNoteCipher`), `createdByName` only with
  `CHARGES_VIEW`, so a member never sees either; the payer's name is not on the account. A member's other plots stay
  the 404 above.
- **Reversal** (`Reversals`): reason trimmed and required (400 `REQUIRED`, `TOO_LONG` over 500); unknown charge 404
  `NOT_FOUND`; a reversal 409 `REVERSAL_NOT_REVERSIBLE`; an adjustment 409 `ADJUSTMENT_NOT_REVERSIBLE` (a later
  recalculation adjusts the original again; `charge_target_guard` refuses it in the database too); the original's month
  is locked `FOR SHARE` and a closed one is 409 `CHARGE_IN_CLOSED_MONTH` (its correction is an adjustment booked by a
  recalculation); reversed before 409
  `CHARGE_ALREADY_REVERSED` (a pre-check, and the unique index for two reversals at once). The new row: kind `REVERSAL`,
  source `MANUAL_REVERSAL`, the negated amount (`Money.negate`), the same plot, charge type, service period and
  `accrued_on`, booked in the original's month (through `BillingMonths`), calculation `{amount, parts: [], readings: []}`.
  Money paid on the charge is taken back in the same transaction (feature 015 slice 5): the plot ledger lock of the
  plot is taken after the charge is found and before the month `FOR SHARE`, and after the reversal row
  `AllocationReleaser.settleCharges` releases every live allocation of the charge (`CHARGE_REVERSED`, by the actor; see
  Payments). Answer 201 with the reversal.
- **Opening balance** (`OpeningBalances`): amount > 0 (400 `AMOUNT_NOT_POSITIVE`), at most 12 integer digits (400
  `INVALID`), date not after today in Europe/Kyiv (400 `DATE_IN_FUTURE`); the plot locked `FOR SHARE` (404
  `PLOT_NOT_FOUND`, 409 `PLOT_ARCHIVED`, 409 `PLOT_COMMON_LAND`); the charge type locked `FOR SHARE` (404 `NOT_FOUND` with
  `errors[{field: chargeTypeId}]`, 409 `CHARGE_TYPE_ARCHIVED`). The row: kind and source `OPENING`, service period and
  `accrued_on` = the date, booked by `BillingMonths` (the month of the date, or the earliest open month after it).
  The optional reason (at most 500, trimmed, blank means none) is stored on the charge (`reason` in the account) and
  in the audit entry. Answer 201 with the charge.

- **Recalculations** (`RecalculationQueries`, read-only, feature 012 slice 1): the list is a page of summaries newest
  first (`cause`, `causeType` from the stored scope, `reason`, `total`, `lineCount`, `plotCount`, `createdAt`,
  `createdByName`); a record has its scope, its lines in booking order (`booked`, `adjustments`, `effective` = their
  sum, `expected`, `difference`, `action`, `bookedIn` = the month of the adjustment, else the new charge, else the
  reversal it booked, `reasons`, the created charge ids) and the differences per charge type in the order of the
  types. Entities `RecalculationRecord` and `RecalculationLineRecord` are `@Immutable`. `ChargeRecord.adjustment` and
  `ChargeRecord.recalculationReversalOf` build the rows a booking will write.
- **Recalculation** (`RecalculationService`, feature 012: cause `MANUAL`, the tariff version causes and the reading
  causes, over the charges of recurring tariffs, of plot meter intervals and of runs). Validation of a manual one:
  `cause.manual` `REQUIRED`; `cause.manual.to` before `from` `INVALID`, after the current month `DATE_IN_FUTURE`
  (400); more than 24 months 409 `RECALCULATION_RANGE_TOO_LONG`; a booking needs `reason` (trimmed, 1-500: `REQUIRED`,
  `TOO_LONG`; the contract's `maxLength` 500 catches it at the boundary too). One `TransactionTemplate` transaction; a
  preview marks it rollback-only and it is the outermost one. Locks: the charging lock -> the plot meters of the scope's
  plots in id order (`MeterCatalog.plotMeterIds`, active and removed, only the resources of the chosen charge types;
  each through `IntervalCharging.lockPoints`: `FOR SHARE` with its points, then its advisory lock, as the listener) ->
  the queue lock -> charge types and tariffs `FOR SHARE` -> every billing month `FOR SHARE` (no month closes under the
  plan). Scope: the months
  `from`..`to`; the chosen charge types (unknown 400 `NOT_FOUND`, inactive or archived `INVALID`) or every active one;
  the chosen plots (`UNKNOWN_PLOT`, `PLOT_ARCHIVED`, `PLOT_COMMON_LAND`) or every chargeable one. Expected:
  `MonthRun.expected` (the month run's plan loop with its water days, `key -> false`) and
  `IntervalCharging.expected` for every locked meter (the listener's `ReadingChargePlanning` with its tariffs, water
  days and losses percentage, `key -> false`) over the intervals whose closing reading is dated in the months; a charge
  type that is not active (water before it is switched on) is not in the scope, so its charges are neither reversed nor
  charged. Booked chains: the `REGULAR` `TARIFF` charges of the scope's plots and types whose key month is in the scope
  (a key that is not a month-run key is left alone) and the `REGULAR` `CONSUMPTION`/`LOSSES` charges without a run of
  the locked meters whose `accrued_on` (the date of the reading that closes the interval) is in the months, with their
  reversals and adjustments; queue: the open `TARIFF_MONTH` items of the scope and the open `INTERVAL` items of the
  locked meters whose interval ends in the months. Then
  `RecalculationPlanner` and `RecalculationDigest` (the line's plot is part of it). The runs (`RunRecalculationTests`):
  the booked losses runs (electricity in the scope) and common consumer runs (`COMMON_CONSUMERS` in the scope) whose
  range ends in the months; with plots chosen, every run with a charge of one of them, as a whole (its other plots are
  shown and changed too). Each is planned again over its range and the nodes or consumers it charged, the run itself
  ignored (`LossesRuns.planAgain`, `CommonConsumerRuns.planAgain`), after the run locks and the shared network
  (`lockRuns`, `MeterNetwork.lockSharedNetwork`). A booking reverses a run of an open month as a whole
  (`reverseLocked`, source `RECALCULATION`, the run and its nodes or lines marked reversed) and books it again as a new
  run of the same range and nodes or consumers (`book`: its value uses and "not charged" items as 011 writes them, no
  run audit entry); in a closed month the lines are adjusted one by one, and a plot new to the run gets a charge of the
  original run (`LOSSES_RUN:{run}:{plot}`, `COMMON_CONSUMER_RUN:{run}:{consumer}:{plot}`).
  A booking with another digest is
  409 `PREVIEW_OUTDATED`; a plan without lines is 409 `NOTHING_TO_RECALCULATE` on booking and an answer with no lines
  on preview. A plan without lines books nothing at all, its queue outcomes included (a preview of it lists no
  `notCharged`); the queue of such a scope is left to the month run and the retry. The booking first takes the plot
  ledger locks of the plots of the lines and of the runs it reverses as a whole (feature 015, see Payments), then
  writes, in order: the
  `recalculation` row (cause, scope with `causeType`, `months`, the chosen `chargeTypeIds` and `plotIds` - empty means
  all -, reason, total, counts, digest, author); the reversals (`ChargeRecord.recalculationReversalOf`, source
  `RECALCULATION`, the reason, in the reversed charge's month); the new `REGULAR` charges with their parts
  (`TariffChargeWriter.bookRecalculated` or `IntervalCharging.bookRecalculated`, which also writes the two
  `meter_value_use` rows of the interval: the base key, or `base#r{recalculationId}` when a charge has the base key, so
  the listener never books the interval again; `recalculation_id` set; in the month 011 would book them in); the
  adjustments (`ChargeRecord.adjustment`: the difference, the original's service period, `accrued_on`, tariff, meter,
  the reason, booked in the earliest open month through `BillingMonths.openForBooking(original.accruedOn)`, calculation
  `{amount, parts: [], readings: []}` and one `charge_part` per version of the expected charge, so those versions count
  as used, and for an interval the two `meter_value_use` rows of the expected interval
  (`IntervalCharging.writePartsAndUse`), so `ReadingUsage` keeps its readings used); the releases of the money its
  reversals and lowering adjustments no longer leave on their charges (`AllocationReleaser.settleCharges`, by the
  actor); the queue (an item that cannot be
  charged opened or refreshed with its tariffs - an `INTERVAL` item also with the first point and day of the interval
  now -, a charged one `RESOLVED`, one with nothing left `OBSOLETE`); the
  `recalculation_line` rows (by plot in natural order, service period, charge type; reasons as
  `RecalculationChangeReason` codes; the charge ids each line booked); one `AuditBatch` with `RECALCULATION_BOOKED`;
  `ChargesBooked` for the plots of the lines and of the runs reversed as a whole. The answer is the contract's
  `Recalculation`: a preview without `id`,
  with the `digest`; a booking with `id`, `reason`, `createdAt`, `createdByName` and each line's created charge ids.
- **Tariff version causes** (`TARIFF_VERSION_ADD`, `_UPDATE`, `_DELETE`, slice 6; functional spec §2.4): the change and
  its recalculation are one transaction, so the version changes only with the recalculation. 403
  `PERMISSION_DENIED` without `TARIFFS_MANAGE` (besides `RECALCULATION_RUN` on the endpoint); `cause.tariffVersion`,
  its `versionId` (update, delete) and `version` (add, update) are `REQUIRED`; a `versionId` that is not a version of
  `tariffId` in the `TariffBook` snapshot is 404 before anything changes. Under the charging lock the change is applied
  through the tariffs facade `TariffVersionChanges` (`Propagation.MANDATORY`: the tariff `FOR UPDATE`, its charge type
  `FOR SHARE`, the endpoint's validation with the field codes under `cause.tariffVersion.version.`, no use check, a
  delete soft) and the plan is made with the change applied: the tariff's charge type (only while it charges), the
  chargeable plots the tariff covers (`TariffBook` snapshot, read after the change), the billing months from the start
  to the end of the old and new validity of the version (`Change.from`/`to`) clipped to the cooperative's first billing
  month and the current month, and the interval charges and runs that close from the first of those months to the end
  of the current month (an interval or run that covers a changed day may close later). A version that touches no such
  month gives an empty plan. The digest's cause is the type, the tariff, the version id of an update or delete and the
  new terms and note (never the id of an added version), so a preview and its booking agree. A preview rolls the
  change back with the plan. A booking: 409 `PREVIEW_OUTDATED` on another digest; a plan without lines saves the change
  alone (no reason needed, no record, `booked` false, `changeApplied` true, the change's audit entry alone); otherwise a
  reason is required (400 `REQUIRED`) and the plan is booked by the same code as a manual one, with cause
  `TARIFF_VERSION`, `cause_ref_id` the version, the scope with `tariffId` and `tariffVersionId`, and the change's audit
  entry (`TARIFF_VERSION_ADDED`/`UPDATED`/`DELETED`, with `recalculationId`) first in the `AuditBatch` of
  `RECALCULATION_BOOKED`. Test: `TariffRecalculationTests`.
- **Reading causes** (`READING_ENTER`, `_CORRECT`, `_DELETE`, slice 7; functional spec §2.5): the reading change and
  its recalculation are one transaction. 403 `PERMISSION_DENIED` without `READINGS_ENTER` (besides `RECALCULATION_RUN`
  on the endpoint); `cause.reading`, its `meterId` (enter), `readingId` (correct, delete) and `reading` (enter,
  correct) are `REQUIRED`. Under the charging lock the change is applied through the meters facade `ReadingChanges`
  (`Propagation.MANDATORY`: the meter `FOR UPDATE`, the reading rules with the field codes under `cause.reading.`
  (`meterId`) and `cause.reading.reading.` (`takenOn`, `value`), no use check, **no `PlotMeterPointsChanged`**), then
  the plan: for a plot meter of a chargeable plot, the meter's intervals closed by a point from the first day the
  change reaches to the last point whose interval it alters (`Change.from`/`through`: around the old and the new date,
  the previous and the next points; the meter locked as the listener locks it), and the booked runs that read the meter
  on a day the change reaches (`meter_value_use` rows of the run meet `Change.from`/`to`, as `LedgerReadingUsage`
  asks: `findBookedReadingMeter`), of the charge type of the meter's resource (for electricity also
  `COMMON_CONSUMERS`); no tariff charge. A node or common consumer meter has runs only. The digest's cause is the
  meter and the old and new date and value (never a reading id: a correction's new row has a new id). A preview rolls
  the change back with the plan. A booking: 409 `PREVIEW_OUTDATED` on another digest; a plan without lines saves the
  change alone (no reason needed, no record, `booked` false, the change's audit entry alone) and applies its queue
  outcomes, since no point event follows (an interval that cannot be charged queued, an item whose interval is gone
  `OBSOLETE`); otherwise a reason is required and the plan is booked with cause `READING`, `cause_ref_id` the meter,
  the scope with `meterId`, the reading corrected or deleted (or the one entered) as `readingId` and the plot, and the
  change's audit entry (`READING_ENTERED`/`REPLACED`/`CORRECTED`/`DELETED`, with `recalculationId`) first in the
  `AuditBatch`. The new charges of an interval get the base key of the new interval (its points' keys), so a later
  point event finds them charged. Test: `ReadingRecalculationTests`.
- **Decisions to know** (functional spec, reviews of slices 4 to 7):
  - An inactive charge type is left out of a tariff version cause (`scope`: only a type that charges), so a rate edit
    of a tariff of an inactive type saves the change with an empty plan and recalculates nothing; after the type is
    made active again, the board runs a manual recalculation.
  - The losses mode and percentage of today are applied to past intervals (`IntervalCharging.expected` reads the
    current settings). After a switch from `PROPORTIONAL` to `FIXED_PERCENT`, a recalculation of electricity over a
    period that has proportional runs adds fixed-percentage losses to the intervals and keeps (or books again) the runs:
    losses twice; the other way round it removes the fixed losses. A known limitation: the board must not recalculate
    electricity over such a period without checking the preview.
  - A chosen plot pulls its whole run into a manual recalculation (a run is recalculated as a whole).
  - Approvals have no recalculation step: approving a pending reading inside a charged interval stays 409
    `READING_IN_USE`; the board enters the reading on the meter card, which goes through `READING_ENTER`.
  - A reading cause always covers every charge type of the meter's resource (`WATER`, or `ELECTRICITY` and
    `COMMON_CONSUMERS` for the runs) and never a tariff fee.

The value rules (amount, date, reason) and the audit values live in `OpeningBalanceRules`, the booking itself in
`OpeningBalances.book`; the import uses both, so the two ways of entering a balance cannot disagree.

Lock order: plot `FOR SHARE` -> charge type `FOR SHARE` -> (a path that reverses or lowers a charge, feature 015) the
plot ledger locks -> billing month `FOR SHARE`; the full order is under Payments. The ledger never locks a `charge`
row (`FOR SHARE` would need `UPDATE`, which `nadel_app` does not have); concurrency on charges is settled by unique
indexes.

**The charging lock** (`ChargingLock`, feature 012): a transaction advisory lock `ledger.charging:{tenant}`
(`pg_advisory_xact_lock(hashtextextended(key, 0))`) that every path booking or reversing charges of a cooperative takes
**first**, before any other ledger lock: the month run booking (before its month lock), the retry of a "not charged"
item of any kind (`NotChargedQueue.retry`), the charges of readings (`ReadingCharges`, before `IntervalCharging`), the
booking, reversal and retry of losses and common consumer runs (in their `lockRuns`, before the run lock), a reversal by
hand (`Reversals`, before the charge's month) and a recalculation, its preview too. Nobody takes it while holding
another ledger lock, so it never takes part in a deadlock; it is reentrant within a transaction (a run retried through
the queue takes it twice). So a recalculation reads the booked chains and books with no other charging path of the
cooperative in between, and a run or a reversal waits for it. Previews of the month run and of the runs take no lock.
Opening balances do not take it (they are never recalculated).

## Opening balances from the import (slice 8)

`OpeningBalanceImportService` (`OpeningBalanceImport`) is the ledger's side of the "Opening balances" / "Вхідне
сальдо" sheet; the `imports` module reads the cells and calls it in its own transaction.

- **Plan** (nothing saved): per row `REQUIRED` (plot number, charge type, amount, date), the value rules of
  `OpeningBalanceRules` (`AMOUNT_NOT_POSITIVE`, `INVALID` for more than 12 integer digits, `DATE_IN_FUTURE`), `INVALID`
  for an amount with more than two decimals (never rounded), `TOO_LONG` for a reason over 500 characters; the charge
  type by name through `tariffs.ChargeTypeCatalog.findByNames` (the stored name, and for a seeded type that was never
  renamed its default name in English or Ukrainian; letter case and extra spaces ignored): `UNKNOWN_CHARGE_TYPE`,
  `CHARGE_TYPE_ARCHIVED` (only an archived type has the name), `INVALID` (two types); the plot: a plot of the file's
  Plots sheet (its plan) or an active plot by number (`UNKNOWN_PLOT`, `PLOT_COMMON_LAND`; a plot whose Plots row has a
  problem is not checked further); `DUPLICATE_IN_FILE` for an earlier valid row with the same plot, charge type and
  date; last, against the plot's opening balances that no reversal cancels: the same charge type, date and amount is
  `UNCHANGED` (so the same file uploaded again books nothing twice), the same charge type and date with another amount
  is `OPENING_BALANCE_EXISTS` with `params.amount` (reverse it on the account first; of several such balances, the
  earliest booked one), anything else is `NEW`.
- **Lock**: the stored plots of the `NEW` rows `FOR SHARE`, then their charge types `FOR SHARE`, in id order.
- **Apply**: every `NEW` row through `OpeningBalances.book` with the source key `OPENING:{importId}:{row}` (the partial
  unique index `charge_source_key_uq` makes a second booking of the row impossible; a violation is 409
  `IMPORT_STALE`), in the month `BillingMonths.openForBooking` gives for the month of its date (one call per month);
  without a reason the source key is the reference to the import. `ChargesBooked` for the plots, at most 500 plots per
  event (`TariffChargeWriter.PLOTS_PER_EVENT`, as the runs); one `OPENING_BALANCE_ENTERED` audit event per balance,
  returned to the import, which publishes them as `AuditBatch`es.

## The read model of the reports (feature 013)

`LedgerReadModel` (implemented by `LedgerReportQueries`) gives the `reports` module the figures it needs without
reading the ledger tables. Rules for every method:

- It runs in the **caller's transaction** (`Propagation.MANDATORY`; a call without one fails with
  `IllegalTransactionStateException`), which the tenant-aware transaction manager has bound to the cooperative, so RLS
  hides other cooperatives' rows as for every ledger query. The caller (`reports.ReportRunner`) opens a plain,
  not read-only, transaction.
- It aggregates in the database (parameterised native queries, no N+1) and answers ids, codes and `Money` only: **no
  personal data**, no names. The caller orders and names charge types (`tariffs.ChargeTypeCatalog`) and plots
  (`plots.PlotCatalog`). The one exception is the separate port `PaymentNotes` (feature 015 §6): the payer notes of
  payments, opened only for a caller with `MEMBERS_VIEW` (`@PreAuthorize`), in the caller's transaction.
- **Paid as of a day** (feature 015, technical considerations 2.3): an allocation counts from its **value date**,
  `GREATEST(payment.received_on, charge.accrued_on)`; a release names the payment and the charge of the allocation it
  takes back, so it shares that date and needs no column of its own (an allocation and its later release cancel out on
  every day). A payment counts from the day it was received; a payment reversal keeps the day of its original
  (`PaymentRecord.reversalOf`), so a reversed payment is never prepayment on any day.
- Aggregates are bounded by their filters (months x charge types, or the plots asked for), so no row limit applies to
  them; `corrections` and `electricityCharged`, lists of single rows, take a `limit` (the caller passes its cap plus
  one); the runs of a range are bounded by the runs times the nodes or consumers of the tree.

Methods:

- `monthTotals(fromMonth, toMonth, chargeTypeIds, plotIds, grouping, asOf)` (charges by month, 013 §3.1): one
  `MonthTypeTotals` per month and charge type that has a row, with `paid` (feature 015 §6: the net allocations to the
  charges of the group whose value date is on or before `asOf`, a lateral sum per charge row over
  `allocation_charge_idx`; `effective` does not depend on `asOf`): `status` (`OPEN`/`CLOSED` of the billing month; `null`
  by service month), `plotCount` (distinct plots with any row of any kind in the group), the sums of `regular`,
  `opening`, `reversals` (negative) and `adjustments` (either sign), and `effective` = `SUM(amount)` of every row
  (money rules 5.4: charges plus adjustments minus reversals). `BILLING_MONTH` groups by `charge.billing_month_id`
  with the predicate of the billing months screen (`ChargeRepository.totalsPerMonthAndType`), so `effective` of a
  month and type is the screen's total cent for cent; `SERVICE_MONTH` groups by the month of `service_from` over the
  days from the first of `fromMonth` to the last of `toMonth` (an adjustment or a reversal keeps the service period of
  its charge, so an adjustment booked in March for January counts in January; a reading interval that starts in the
  previous month counts there). `chargeTypeIds` empty = every type; `plotIds` `null` = every plot (a street resolved by
  the caller), empty = no rows. Rows by month, then type id; `toMonth` before `fromMonth` is an
  `IllegalArgumentException`. Test: `LedgerReadModelTests` (a worked ledger with a reversal, an opening balance and
  later-month adjustments in both groupings, reconciliation with `GET /billing-months` for every listed month, the
  filters, another cooperative, the caller's transaction).
- `debtsAsOf(asOf, chargeTypeIds, plotIds)` (plot balances and debtors, 013 §3.2 and §3.3, 015 §6): one `PlotDebts` per
  distinct plot asked (a plot without counted charges and without prepayment, or an unknown id, is `PlotDebts.none`:
  no types, `0.00`, no date), with `DebtCalculator`'s rule after payments (money rules 2, 5.4, 9, feature 015 §2.5)
  applied in SQL to the charges with `accrued_on <= asOf` of the given types (empty = every type) and to the
  allocations counted as of `asOf`: `chargedByType` is the signed sum per type (reversals negative, adjustments with
  their sign, opening balances included; a type that sums to `0.00` is kept), `paidByType` the net allocations to those
  charges, `byChargeType` the debt (charged − paid), `charged`, `paid` and `total` their sums, `prepayment` the plot's
  payments received on or before `asOf` minus all its allocations counted by then, never below zero (every type,
  whatever the filter; a second query per chunk), `oldestUnpaid` the oldest `accrued_on` among the counted charges that
  are neither a reversal nor an adjustment, are not reversed by a counted reversal and whose amount plus their counted
  adjustments minus what is paid on them is above `0.00`, and `daysOverdue` = `asOf - oldestUnpaid` in days, computed
  here (null without a date). The 5-argument `PlotDebts` constructor is the same for a plot without payments. A reversal and an adjustment keep
  the debt date and charge type of their charge, so both filters keep or drop a charge with its corrections, and the
  per-type answer folds into `DebtCalculator`'s per-plot one. The day is the debt date, not the booking time: an
  adjustment booked in March for a January charge counts as of any day from the charge's date, as on the account. As
  of today with every type it equals the plot account and the `plot_status` cache, prepayment included. One query per
  500 plots
  (`charge_plot_type_idx`). Test: `LedgerDebtsAsOfTests` (a worked ledger with a reversal, an opening balance,
  later-month adjustments, a fully adjusted and a reversed oldest charge, past days, the type filter, reconciliation
  with `GET /plots/{id}/account` and the status cache, another cooperative, the caller's transaction).
- `corrections(fromMonth, toMonth, chargeTypeIds, kinds, limit)` (corrections, 013 §3.8): one `CorrectionRow` per
  `REVERSAL` or `ADJUSTMENT` row booked in the billing months from–to (`kinds` empty = both, `chargeTypeIds` empty =
  every type), joined to the charge it corrects (`reverses_charge_id` or `adjusts_charge_id`: `originalChargeId`, its
  service period and its booked amount) and to its `recalculation` (`recalculationId`, `cause`
  `MANUAL`/`TARIFF_VERSION`/`READING`): `kind`, `bookedAt` (`created_at`) and `bookedOn` (that instant as a Kyiv date,
  on the `Clock`'s zone), `billingMonth`, `plotId`, `chargeTypeId`, `amount` (signed as booked), `reason` (the reason
  booked with the row: a hand or run reversal's, or the recalculation's, which a recalculation stores on every row it
  books; user content) and `bookedBy` (`created_by`, a member id the caller names through `members`). **Source**
  (`CorrectionSource`), derived in SQL: `source = 'RECALCULATION'` -> `RECALCULATION` (every adjustment, and the
  reversals of a recalculation, also of a run it rebooks); a `MANUAL_REVERSAL` row whose run (`run_id`, a
  `losses_run` or `common_consumer_run`) is `REVERSED` with `reversed_at` equal to the row's `created_at` ->
  `RUN_REVERSAL` (the run reversal writes the run and its reversals from one `Instant`); every other
  `MANUAL_REVERSAL` row -> `MANUAL_REVERSAL` (one charge by hand, also a single charge of a run reversed before its
  run). Rows by billing month, then `created_at`, then id; at most `limit` (>= 1) rows; `toMonth` before `fromMonth`
  or `limit` below 1 is an `IllegalArgumentException`. Index: `charge_month_type_idx`. Test: `LedgerCorrectionsTests`
  (a hand reversal, a recalculation's adjustment and reversal with cause and reason, a hand-reversed charge of a run,
  a losses run and a common consumer run reversal, the Kyiv day of a booking late in the UTC evening, the kind, month
  and type filters, the limit, another cooperative, invalid arguments, the caller's transaction).
- `chargedInYear(chargeTypeCode, year, plotIds)` (land tax, 013 §3.7): per distinct plot asked (in order of first
  appearance; `0.00` for a plot without such charges or unknown), the effective amount (money rules 5.4) charged by the
  type with the code (resolved through `tariffs.ChargeTypeCatalog`; an unknown code gives `0.00` everywhere) for the
  year: `SUM(amount)` of its `REGULAR`, `REVERSAL` and `ADJUSTMENT` rows whose `service_from` is from 1 January to
  31 December of the year. A reversal and an adjustment keep their charge's service period, so they count in its
  year whatever month they were booked in (a December charge booked in January counts in December's year). Opening
  balances are left out: they carry a debt from before the ledger, not a charge of the year. One query per 500 plots
  (`charge_plot_type_idx`). Test: `LedgerChargedInYearTests` (the year boundary, a late-booked December charge, an
  adjustment and a reversal, an opening balance, another type and an unknown code, another cooperative, the caller's
  transaction).
- `chargedByType(from, to)` (report templates, `totals.charged.<type>`, 013 §4.1): the same rule per charge type over
  a range of days and every plot of the cooperative (archived plots and common land included): `SUM(amount)` of the
  `REGULAR`, `REVERSAL` and `ADJUSTMENT` rows whose `service_from` is from `from` to `to`, grouped by
  `charge_type_id`; a type without such rows is absent (`0.00`). Opening balances are left out. One query (bounded by
  the charge types; it reads the cooperative's charges through `charge_tenant_idx`). `to` before `from` is an
  `IllegalArgumentException`. Test: `LedgerChargedInYearTests` (a range of three months and of one, without the
  opening balance, another cooperative, a range backwards, the caller's transaction).
- `electricityCharged(from, to, limit)` (electricity report, "Charged" sheet, 013 §3.4): one `ChargedRow` per ledger
  row (`REGULAR`, `REVERSAL`, `ADJUSTMENT`; never an opening) of the `REGULAR` charges of source `CONSUMPTION` or
  `LOSSES` of the type `ELECTRICITY` (resolved by code through `tariffs.ChargeTypeCatalog`) and of source
  `COMMON_CONSUMER` (type `COMMON_CONSUMERS`) whose service period overlaps the days `from`..`to`, each correction its
  own row with the original charge's service period, source (`ChargedSource`), meter, run and, for a common consumer
  share, the consumer its calculation names (`consumerMeterId`), whatever month it was booked in. Water, tariff charges and opening balances are left out. **One row per charge, never per part**: a
  charge's parts are added exactly and rounded once (money rules 6.5), so an amount per part would round twice; the row
  carries its `parts` (`ChargedPart`: tariff version, days, exact quantity, rate, losses node) and `rate()` is the
  price per kWh when they share one, `null` when the rate changed within the charge's days. `quantity` is the exact
  kWh the row adds: a regular charge its parts' sum; a reversal minus the parts of the state it cancels (its `parts`
  are those, negated); an adjustment its own parts (the charge as recalculated, written by the recalculation) minus
  those of the state before it (the previous adjustment of the charge, else the charge), so a charge's rows add up to
  its effective kWh as to its effective amount. Runs are listed like any charge: a reversed run brings its charges and
  their reversals (they add up to zero). Rows by service period, plot, source, booking time and id, each charge
  followed by its corrections in booking order; at most `limit` (>= 1) rows; `to` before `from` or `limit` below 1 is
  an `IllegalArgumentException`. Two queries: the rows (correlated lookup of the previous adjustment,
  `charge_adjusts_idx`), then the parts, 500 charges per `IN` list. Test: `LedgerElectricityChargedTests` (an interval
  with fixed-percentage losses, two later-month adjustments of the same charge, a charge with two rates reversed by
  hand, a common consumer share, charges outside the range and of other types, reconciliation with the plot account,
  the limit, another cooperative, invalid arguments, the caller's transaction).
- `waterCharged(from, to, plotIds, limit)` (water report, 013 §3.5): the same `ChargedRow`s and query as
  `electricityCharged` (one SQL template, `CHARGED`, with a predicate per report) for the plots asked (500 per `IN`
  list; the chunks' rows are merged in the query's order, `uuid` compared as unsigned bytes like PostgreSQL, and cut
  to `limit`): the `REGULAR` charges of source `CONSUMPTION` (m³) and `TARIFF` (the fixed water fee) of the type
  `WATER` (by code through `tariffs.ChargeTypeCatalog`), and **a well pump's share**: the `COMMON_CONSUMER` charges
  (type `COMMON_CONSUMERS`) whose `calculation ->> 'split'` is `CONNECTED_PLOTS`. That split shares a consumer among the
  plots connected to it as their water source (money rules 6.7, 11), and it is the only link between a common consumer
  and water the ledger stores (the connections are the meters module's table); a consumer split another way (street
  lighting, `EQUAL_PER_PLOT`) is not water. Every reversal and adjustment is its own row with the original service
  period. A pump share is also a row of `electricityCharged`; a report showing both counts it in each. `quantity` is
  m³ for consumption, kWh for a pump share and **null for a `TARIFF` row** (a fixed fee measures nothing; its parts
  count plots or sotkas). An empty `plotIds` gives no rows. Test: `LedgerWaterChargedTests` (consumption with a
  later-month adjustment, a fixed fee reversed by hand, a well pump share and a lighting share, a charge outside the
  range, other types and plots, the sum per plot, the limit over three chunks of plots, another cooperative, invalid
  arguments, the caller's transaction).
- `openIntervalItems(meterIds)` (missing readings, 013 §3.6): per distinct meter asked (in order of first appearance;
  `0` without items or for an unknown id) the number of `not_charged_item` rows of kind `INTERVAL` and status `OPEN`
  (the items `GET /not-charged` lists) whose `meter_id` it is, 500 meters per query. Test:
  `LedgerOpenIntervalItemsTests` (open, resolved and other-kind items, another cooperative, the caller's
  transaction).
- `paidByType(from, to)` (report templates, `totals.paid.<type>`, 015 §6): per charge type the net allocations, counted
  as of `to`, to the `REGULAR` charges whose `service_from` is from `from` to `to`: the charges `chargedByType` counts
  for the same range (opening balances have no service period of the range), so paid and charged share the accrual
  basis. A type without such allocations is absent (`0.00`). `to` before `from` is an `IllegalArgumentException`.
- `prepaymentAsOf(asOf)` (report templates, `totals.prepayment`): the prepayment of every plot of the cooperative as of
  the day (as in `debtsAsOf`), summed.
- `payments(filter, limit)` (payments report, 015 §6): one `PaymentRow` per `PAYMENT` row of the `PaymentFilter`
  (billing months **or** days received, optionally a source and some plots; null plots = every plot, empty = no rows):
  receipt number, bank reference, day received, billing month, plot, payer member id, source, amount, `allocated` (its
  net allocations now), `remaining` (`amount - allocated`, `0.00` once reversed), `reversed` (a `PAYMENT_REVERSAL`
  points at it; the reversal row itself is never listed) and group. **Never the payer note** (`PaymentNotes`). Rows by
  day received, time booked and id; at most `limit` (>= 1) rows. Test: `LedgerPaymentsReadModelTests` (the as-of rule
  with a payment after the day, a release, an allocation to a later charge and a reversed payment; the type filter and
  the prepayment; reconciliation with `GET /plots/{id}/account` as of today; `paidByType` and `chargedByType` on the same
  accrual basis; `prepaymentAsOf`; `monthTotals` paid as of two days while `effective` equals `GET /billing-months`;
  `payments` by months, days, source, plot and limit; `PaymentNotes` with and without `MEMBERS_VIEW`; the caller's
  transaction).
- `paymentsByIds(ids)` (the payment and reversal messages, feature 017 §2.4): the same `PaymentRow` for each id of kind
  `PAYMENT` (`reversed` set for a reversed one); unknown ids, another cooperative's payments and reversal rows are
  absent; 500 ids per query. Test: `PaymentEventsTests` (with the events `PaymentRecorded` and `PaymentReversed`).
- `cashReceipt(paymentId)` (the printed cash receipt, feature 016 §2.8, read by `documents`): the payment group of
  any of its parts as one `CashReceiptData`: group, receipt number (null for a bank payment, which is answered too, with
  its source, so the caller refuses it), source, day received, the member who entered it (`created_by`), the payer
  member and every `PAYMENT` part in the order entered with its plot, amount, `reversedAt` (the `created_at` of its
  reversal), its `placements` now (per charge the net of its allocations and releases above `0.00`, in the planner's
  order: debt date, charge type position, service start, booking time, id; with the charge's type, opening flag,
  billing month and service period) and `unallocated` (`amount` minus the placements, `0.00` once reversed). Empty for
  an unknown id, another cooperative's payment and a reversal row. No payer note (`PaymentNotes`). Implemented by
  `internal.CashReceiptQueries` (four small reads). Tests: `documents` `CashReceiptTests`, `ReceiptAccessTests`.
- `invoiceLedgers(plotIds, month, asOf)` (the invoice of a plot for a billing month, feature 016 §2.3, read by
  `documents`): per plot one `InvoiceLedger` by the **booking-month rule** (money rules 13, `MonthMovements` below):
  the month's status as of `asOf` (`CLOSED` only when it was closed by then; null when the month has no row) and its
  closing time; per charge type with any movement up to the month (`TypeMovement`: start, charged, paid, end); every
  charge booked in the month (`Line`: kind, the source of the charge or, for a correction, of the charge it corrects,
  amount, service period, `corrects` = the billing month of the charge a reversal or an adjustment corrects when it is
  earlier, and the stored `calculation` copied as codes and plain decimals: the parts' basis, frequency, quantity,
  unit, rate, days and dates, the readings' meter, day and value, `lossesPercent`, `consumption`, `consumerName`); the
  prepayment at the end of the month; and the payments booked after it, created by `asOf` and not reversed by then
  (`LaterPayment`); and every payment row booked in the month (`MonthPayment`, `MonthMovements.bookedIn`: a payment
  and a payment reversal alike, the reversal as its own row with the negated amount, in the order received; the
  receipt number and the bank reference, a reversal those of the payment it reverses). Only rows created at or before
  `asOf` count, so a caller reading every plot of a build with one
  `asOf` inside one `REPEATABLE READ` transaction gets one consistent picture. Implemented by
  `internal.InvoiceLedgerQueries`: three parameterised native queries per call (the billing months; the plots' charges
  booked in or before the month with the target of each correction; the plots' payments and allocations as one
  `UNION ALL` with the billing months of each allocation's payment and charge and each payment's receipt number and
  bank reference), plots 500 per `IN` list; the caller
  passes a bounded number of plots (a chunk of a build). Tests: `MonthMovementsTests` (pure, the worked examples 1-5),
  `documents` `InvoiceOnRequestTests` (example 5 end to end, a closed month against `debtsAsOf` of its last day).
- `firstBillingMonth()` (feature 016): the month of the earliest `billing_month` row, empty while the cooperative has
  none; the first month `GET /billing-months` lists. The month build of invoices and its print set answer 404 before it,
  as `GET /billing-months/{month}` does.
- `lossesRunsInRange(from, to)` and `commonConsumerRunsInRange(from, to)` (money rules 6.6, 6.7): the nodes of every
  losses run (`LossesRunNodeRow`) and the consumers of every common consumer run (`CommonConsumerRunLineRow`) whose
  range overlaps `from`..`to`, as booked (`losses_run_node`, `common_consumer_run_line`: outcome, consumption, children,
  losses and percent, or split, consumption and plot-days, amount; quantities exact from the stored three decimals),
  with the run's range, status, reversal time, billing month and total, `plotsShared` (the shares stored with the
  node or line) and `plotsCharged` (the distinct plots with a `REGULAR` charge of the run for the node, by a part's
  `node_meter_id`, or for the consumer, by its calculation's `consumerMeterId`; a plot a recalculation added to a run
  of a closed month included). **Reversed runs are included** with `RunStatus.REVERSED`, consistently with
  `electricityCharged`, which lists their charges with their reversals; the caller shows or leaves them out. Rows by
  the run's range, booking time and id, then position. Test: `LedgerElectricityRunsTests` (a proportional losses run
  booked, reversed and booked again, a common consumer run, their charges in `electricityCharged`, another
  cooperative, invalid ranges, the caller's transaction).

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
  negative; code, stored name and `nameCustomized` like the account), `total` and `received` (feature 015 §6:
  `SUM(amount)` of the payment rows booked in the month, `PaymentRepository.receivedPerMonth`, reversals of payments
  negative; a payment received in a closed month is booked in the earliest open month and counts there, its reversal
  in the month the reversal is booked in; `0.00` for a month without a row or a payment). No month at all: `[]`.
- **Month** (`details`): the same (`received` by `PaymentRepository.receivedInMonth`) plus `closedByName` (through `audit.ActorNames`) and `checklist{runDone,
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
  month's charge of any of them counts as charged (see Idempotent) there is nothing new. Otherwise each one's amount is
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
- **Idempotent:** a key counts as charged (`ChargeKeys`) while a charge with it, or with its `base#r{recalculation}`
  key, stands or was reversed by hand (`MANUAL_REVERSAL`); such a key is never booked again, so a rerun creates only
  what is missing (a plot or tariff added later) and a tariff charge reversed by hand is not re-created (a
  recalculation leaves it frozen too). A key whose every charge a recalculation set aside
  (a `REVERSE` line in an open month, or an
  `ADJUST` line that brought a closed month's charge to `0.00`, that queued the case) does not count: the run, a retry
  or the listener charges the case again once it can be charged, under `base#r{recalculation}` (the recalculation that
  made the reversal or the adjustment; the base key stays the original charge's), in its own month if open, else in the
  earliest open month with its own service period. Runs of one month are serialised by a transaction advisory lock (`ledger.month-run:{tenant}:{YYYY-MM}`).
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
- **Locks** (`TariffChargeWriter`): the charging lock (`ChargingLock`) -> the month's advisory lock
  (`ledger.month-run:{tenant}:{YYYY-MM}`) -> the queue advisory lock (`ledger.not-charged:{tenant}`) -> every charge
  type `FOR SHARE` (id order) -> every tariff `FOR SHARE` (`TariffBook.lockShared`, id order) -> the billing month `FOR SHARE` (`BillingMonths.openMonthForRun`)
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
  `RUN_ALREADY_REVERSED`; the plot ledger locks of every plot the run charged (one sorted call, feature 015), then the
  run's month `FOR SHARE`, a closed one 409 `RUN_IN_CLOSED_MONTH`. Every charge of the run
  that is not reversed yet (one may have been reversed by hand) gets a `REVERSAL` row with the run's reason (source
  `MANUAL_REVERSAL`, the run's `run_id`, in the charge's month); the run becomes `REVERSED` with the time, person and
  reason, its nodes `run_status = REVERSED` (their days are free again, and its value uses no longer count). Money paid
  on the reversed charges is released (`AllocationReleaser.settleCharges`, `CHARGE_REVERSED`, by the actor). Audit
  `LOSSES_RUN_REVERSED` (`from`, `to`, `count`, `total` of the reversals, `reason`); `ChargesBooked`. The ledger stays
  append-only; the run rows change only by `BOOKED` -> `REVERSED` (trigger `run_reversal_guard`, V17).
- **History and details:** newest first, with `billingMonth`, `createdByName`, `total`, `plots` (charges),
  `chargedNodes`, `skippedNodes`, `reversible` (`CHARGES_REVERSE`, booked, open month) and the reversal; the details add
  the nodes as booked and the charges with their exact kWh (from their parts) and whether each was reversed.
- **Locks:** the charging lock (`ChargingLock`) -> the cooperative's advisory lock of losses runs
  (`ledger.losses-runs:{tenant}`; bookings, reversals and retries one at a time) -> every electricity meter
  `FOR SHARE` -> the queue lock -> the charge types and tariffs `FOR SHARE` -> the billing month `FOR SHARE`: the order
  of the month run.
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
- **The split** (`CommonConsumerSettings.ofChain`): the settings belong to the consumer, the whole chain of its meters;
  the split is the one of the latest meter of the chain that has its own split (none: the cooperative's
  `commonConsumerDefaultSplit`), the service users those of the latest meter that has users (none: no users), each
  found on its own (a newer meter with only users does not hide an older meter's split). The plots that may share it are the
  chargeable plots (active, not common land, `PlotCatalog.findChargeable`): `EQUAL_PER_PLOT` all of them, `SERVICE_USERS`
  the marked ones, each with the days of the range; `CONNECTED_PLOTS` those connected to any meter of the chain
  (`WaterConnections.connectedToSource`) on their connected days; `NOT_CHARGED` none.
- **The kWh** (`CommonConsumerSplitCalculator`, pure): `CONNECTED_PLOTS` per plot-day (`consumption x plot's days /
  sum of the days`), `EQUAL_PER_PLOT` and `SERVICE_USERS` equally; exact quantities.
- **The price** (`CommonConsumerPlanning`, pure): each plot's kWh of a consumer priced with its `COMMON_CONSUMERS`
  tariff (`TariffResolver`; `ReadingChargeCalculator` spreads the kWh evenly over the plot's own days of the split,
  split by days when the rate changes, one rounding); one charge per plot per consumer. A share that cannot be priced is
  listed with `reason` and not charged; `0.00` charges nothing. A consumer none of whose plot shares can be priced is
  still booked `CHARGED` with `0.00` (no charges) and blocks its range for later runs, and the preview shows each plot's
  `reason` - the same as a losses node. `noTariff` when the type is inactive or archived or has
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
  consumer, charge type `COMMON_CONSUMERS`) per unknown consumer; settles the open items of a charged consumer whose
  range meets the run's (`CommonConsumerRuns.settle`, as `LossesRuns.settle` does, the day gaps by `UnchargedDays`:
  `RESOLVED` when the booked runs that charged the consumer cover every day of the item; else the item is `RESOLVED`
  and narrowed to the days left, an open item `COMMON_CONSUMER:{consumer}:{from}:{to}` per run of uncharged days unless
  one is open already; an item none of whose days is charged stays) and closes the item of a `NOT_CHARGED` or
  `NO_PLOTS` consumer for the same range (`OBSOLETE`); audit `COMMON_CONSUMER_RUN_BOOKED` (entity type `COMMON_CONSUMER_RUN`, label `from/to`, values `from`,
  `to`, `billingMonth`, `consumers`, `count`, `total`) and `ChargesBooked`.
- **Reversal, history and details:** as for losses runs (`RUN_ALREADY_REVERSED`, `RUN_IN_CLOSED_MONTH`, reason
  required; the plot ledger locks of the run's plots before the month and the releases of what was paid on its
  charges; audit `COMMON_CONSUMER_RUN_REVERSED` with `from`, `to`, `count`, `total`, `reason`). The summary counts
  `plots`, `charges`, `chargedConsumers` and `skippedConsumers`; the details give each plot share its `chargeId` and
  `reversed`.
- **Retry** of a `COMMON_CONSUMER` item (`CommonConsumerRuns.retry`, under the locks of a booking, the item
  `FOR UPDATE` after the queue lock): a run of that consumer alone over the item's range. Charged now -> booked and
  `RESOLVED`; charged by other runs meanwhile -> `RESOLVED` (for some of its days only: narrowed to the days left, as a
  booking does); `NOT_CHARGED`, `NO_PLOTS` or no longer a consumer of the
  range -> `OBSOLETE`; still unknown, or no tariff -> open. Audit `NOT_CHARGED_RETRIED` (entity type `METER` about the
  consumer meter).
- **Settings of a consumer** (`CommonConsumerBillings`): `GET` gives `meterId`, `name`, `active` (of the meter asked),
  `split` (absent: the cooperative's default), `effectiveSplit` and `servicePlotIds` (natural order) of the consumer:
  through any meter of its chain (`MeterCatalog.chainOf`) the settings a run uses, resolved as above.
  `PUT {split?, servicePlotIds}` through any meter of the chain saves them on the chain's latest meter (absent `split`
  deletes its row) and removes the rows of the chain's other meters (`CommonConsumerSettings.saveConsumer`), so `GET`
  through any meter of the chain then shows what was saved and the next run charges with it. A meter that is not a common consumer meter (removed ones are accepted) is 404
  `NOT_FOUND`; `servicePlotIds[i]` `UNKNOWN_PLOT`, `DUPLICATE`, `PLOT_ARCHIVED`, `PLOT_COMMON_LAND` (400). One change of
  a consumer at a time (advisory lock by the chain's first meter); audit `COMMON_CONSUMER_BILLING_CHANGED` (entity type
  `METER` about the meter asked, label the meter name, `split` and `servicePlots` as plot numbers, only what changed). A change applies to the next run only.
- **Locks:** the charging lock (`ChargingLock`) -> the cooperative's advisory lock of common consumer runs
  (`ledger.common-consumer-runs:{tenant}`) -> every electricity meter `FOR SHARE` -> the queue lock -> the charge types
  and tariffs `FOR SHARE` -> the billing month `FOR SHARE`: the order of the month run and the losses runs (no
  transaction takes both run locks).

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
- **The decision** (`ReadingChargePlanning`, pure): key `CONSUMPTION:{fromKey}:{toKey}`; a key that counts as charged
  (`ChargeKeys`, as for the month run: a charge stands or was reversed by hand) is never booked again, so a redelivered
  event writes nothing and an interval reversed by hand is not re-created (a correction is a new reading with a new
  key); an interval only a recalculation set aside (reversed, or adjusted to `0.00` in a closed month) is charged again once it can be, under `base#r{recalculation}`. A key also counts as charged while a charge of the same interval (meter, source and point dates, `ChargeIdentity`) stands under another key (`ChargeKeys.standingOfInterval`): a reading corrected by a recalculation that adjusted the interval or left its amount unchanged keeps the charge under the old reading's key, and a redelivered event books no duplicate. `tariffs.TariffResolver` over the days of the interval for
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
  reading again (a manual recalculation of water does).
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

## Payments (tag `payments`, feature 015 slices 2 to 5, owner review of 28.10.2026)

| Endpoint | Operation | Who |
|---|---|---|
| `GET /payments?from&to&source&plotId&month&page&size` | The payments (kind `PAYMENT`; a reversal marks its payment `reversed`), newest first (`received_on`, then `created_at`, then id), filtered by `received_on`, source, plot and the billing month they are booked in; each with its receipt number or bank reference, the payer's short name, `allocated` (net allocations), `prepayment` (what is left, `0.00` once reversed) and `groupSize` (the payments of its group: the parts of a split, reversed ones included; 1 for a payment of one plot). `from` after `to` is 400 (`from` `INVALID`) | `PAYMENTS_VIEW` |
| `GET /payments/{id}` | The card: the payment, `currentAllocations` (where its money is now: per charge the net of its allocations and releases, only above `0.00`, in planner order, with charge kind, type, billing month and period), its allocations and releases (the history, oldest first; among rows of one time a release after the allocations and the system's rows after a member's; each with the charge type, period, origin or release cause, `createdAt`, `system` for rows written by the system and `createdByName`, the author's current name through `audit.ActorNames`, absent for the system), the other parts of its group, the reversal, the author's current name, `prepaymentAllocatable` for the payment's plot (as on the account), and `payerNote` only for a viewer with `MEMBERS_VIEW`. Unknown, another cooperative's or a reversal's id: 404 `NOT_FOUND` | `PAYMENTS_VIEW` |
| `POST /payments?preview=true\|false` | One plot (`plotId`) or a split over the plots of one payer (`split`, see Split below), exactly one of them. For one plot, `lines` absent (or null) saves the proposal, an empty list places nothing by lines. While `autoAllocatePrepayment` is on, the remainder is placed at once (see Automatic placement of a remainder). Preview (200): `valid`, `problems`, per part every open charge of the plot in planner order with `open` and `proposed` (the proposal's or the caller's amount, `0.00` when nothing), `resultLines` (the rows a save writes, `automatic` true for the system's), `allocatedTotal` (everything placed), `automaticTotal`, the prepayment the payment leaves, the plot's `debt` (the sum of its open amounts), for a split also `split` (the payer and every plot of the split with its `debt` and `proposedAmount`), and the `digest` (absent when invalid); nothing written, no lock. Save (201): the same plus `payments` (id, group, receipt number of a cash payment; one per part above zero) | `PAYMENTS_ENTER` |
| `POST /payments/{id}/reverse` | `{reason}` (1-500, trimmed). Releases every live allocation of the payment (`PAYMENT_REVERSED`), then writes the `PAYMENT_REVERSAL` row; 201 with the card. 409 `PAYMENT_ALREADY_REVERSED` (reversed before, also two at once, or a reversal row's id); 404 `NOT_FOUND` | `PAYMENTS_REVERSE` |
| `POST /payments/{id}/reallocate?preview=true\|false` | `{lines?, digest?}`. Preview (200): `valid`, `problems`, `current` (what each live allocation of the payment holds now), `proposal` over the open charges as if they were released (`amount` the payment's; `resultLines` with the automatic placement of the remainder while the setting is on), `digest` (absent when invalid). Save (201): releases (`REALLOCATED`), new allocations (origin `REALLOCATION`) and the automatic ones (`PREPAYMENT_AUTO`) in one transaction. 409 `PAYMENT_ALREADY_REVERSED` for a reversed payment or a reversal row | `PAYMENTS_ENTER` |
| `POST /plots/{id}/prepayment/allocate?preview=true\|false` | `{lines?, digest?}`. Preview (200): `valid`, `problems`, `proposal` over the plot's open charges, its `amount` the prepayment, `prepayment` what stays; `digest` (absent when invalid). Save (201): allocations origin `PREPAYMENT_MANUAL` by the actor; nothing automatic. 409 `NOTHING_TO_ALLOCATE` (no prepayment or no open charge); 404 `PLOT_NOT_FOUND` | `PAYMENTS_ENTER` |

`PaymentService.enter`, in this order: the values (`PaymentRules`: `amount` `AMOUNT_NOT_POSITIVE`/`INVALID`,
`receivedOn` `DATE_IN_FUTURE`, `plotId` `REQUIRED`, `payerNote` `TOO_LONG`, `bankReference` `TOO_LONG` or `INVALID` on
cash; texts trimmed, blank = none); the plot exists in the cooperative (404 `PLOT_NOT_FOUND`), is not archived (409
`PLOT_ARCHIVED`) and not common land (409 `PLOT_COMMON_LAND`); the payer is a member of the cooperative through
`members.MemberDirectory.find` (`payerMemberId` `MEMBER_NOT_FOUND`; an archived member may pay, a former owner paying an
old debt). A save then takes its locks; the ledger of the plot (`PlotLedgerQueries.load`) is read under the plot ledger
lock; the caller's lines are checked by `AllocationPlanner.validate` (`LinePlacement.requireValid`: 400
`VALIDATION_FAILED` with `lines[j].chargeId` `NOT_FOUND`/`DUPLICATE` or `lines[j].amount` `AMOUNT_NOT_POSITIVE` first,
then `ALLOCATION_EXCEEDS_PAYMENT` on `lines`, then `ALLOCATION_EXCEEDS_CHARGE` on `lines[j].amount`); the remainder is
placed automatically (below); a `digest` that differs from `PaymentDigest.entry` of that ledger and those lines is 409
`PREVIEW_OUTDATED` and nothing is written. The payment is booked in `BillingMonths.openForBooking(received_on)` (a
payment received in a closed month counts in the earliest open month and keeps its `received_on`), its own group, with
the next receipt number for cash; then its allocations (origin `ENTRY`, `created_by` the actor; the automatic ones
`PREPAYMENT_AUTO`, `created_by` null, same `created_at`; the payment is flushed first because inserts are ordered by
entity), the audit entry (`PAYMENT_RECORDED` unchanged: the automatic lines are ledger rows, not audit entries) and
`PaymentsChanged`. Logs carry ids and counts only.

**Automatic placement of a remainder** (owner decision of 28.10.2026, changes FS 2.4: "with automatic allocation on,
prepayment never waits while the plot has open debts"). While `settings.CooperativeSettings.autoAllocatePrepayment()`
is on, what the proposal's or the caller's lines leave of a payment (of each part of a split, of a reallocated payment)
is placed in the same transaction by `AllocationPlanner.completeWithRemainder` over the charges still open after the
lines, oldest first in the planner's order, as allocations of origin `PREPAYMENT_AUTO` by the system. The treasurer
still chooses the lines; only the free remainder is placed. Prepayment stays only when the plot has no open charge left
(it waits for new charges and `PrepaymentAllocator`) or while the setting is off. Origin `PREPAYMENT_AUTO` was chosen
over a new origin because it needs no migration (an older release reading a new enum value would fail) and the rows are
exactly "placed by the system"; on the account they are also listed among `prepaymentAllocations` at the time of the
entry. Only the payment's own remainder is placed: a prepayment that was already waiting (the setting switched on
later, no sweep) is still placed at the plot's next `ChargesBooked`.

**Previews with problems** (all four previews: entry, split, reallocation, prepayment allocation). A preview never
answers 400 for its lines: it answers 200 with `valid: false` and `problems` (`AllocationProblem`: `code`
`ALLOCATION_EXCEEDS_CHARGE`/`ALLOCATION_EXCEEDS_PAYMENT`/`AMOUNT_NOT_POSITIVE`/`NOT_FOUND`/`DUPLICATE` with the
part's `plotId`, the `line` index in the lines sent and its `chargeId`; `ALLOCATION_EXCEEDS_PAYMENT` with the plot only;
`SPLIT_TOTAL_MISMATCH` with neither), the whole table of open charges with the requested amounts as `proposed`
(repeated charges summed), `allocatedTotal` = the requested amounts above zero, `amount`, `prepayment` never below
`0.00`, no `resultLines`, no automatic placement and no `digest`. A save with the same request is still 400 with the
codes above; request-shape errors (`amount` format, missing fields, a part's plot, `lines` `REQUIRED` on a prepayment
allocation, `NOTHING_TO_ALLOCATE`) stay 400/409 for previews too. Previews take no lock (plots read without
`FOR SHARE`, no plot ledger lock, no receipt counter) and write nothing.

**Split** (feature 015 §2.6, slice 3; `split: {payerMemberId, parts?: [{plotId, amount?, lines?}]}` instead of
`plotId`; `plotId` and `split` together are 400 `split` `INVALID`, `lines` with `split` 400 `lines` `INVALID`, a
top-level `payerMemberId` other than `split.payerMemberId` 400 `payerMemberId` `INVALID`). The payer must be a member
(`split.payerMemberId` `MEMBER_NOT_FOUND`); the plots of the split are the payer's current plots
(`identity.OwnedPlotsSource.currentPlotsOf`: owned now, any share) that are neither common land nor archived, in natural
plot order (`PlotCatalog.inNaturalOrder`); none is 400 `split.payerMemberId` `NOT_FOUND`. Their ledgers are loaded in
one `PlotLedgerQueries.load`; a plot's debt is the sum of what is open on its charges (`PaymentViews.debt`), and
`PaymentSplitter.split` proposes the parts. Without `parts` the proposal is used (every plot is a part, `0.00` ones
included). With `parts` (not empty, else `split.parts` `REQUIRED`): each `plotId` a plot of the split
(`split.parts[i].plotId` `NOT_FOUND`, also for a plot of another member, an archived plot or common land) at most once
(`DUPLICATE`); `amount` `0.00` or more (`split.parts[i].amount` `INVALID` below zero or over 12 integer digits), absent
= the proposed part of the plot; a part of `0.00` is shown in the answer but not saved. The parts are put in natural
plot order; their sum must equal `amount` (400 `SPLIT_TOTAL_MISMATCH`, field `split.parts`). The digest
(`PaymentDigest.entry(amount, payer, parts)`) covers the amount, the payer, the parts (plot, amount) and each part's
open charges; a stale one is 409 `PREVIEW_OUTDATED`. Each part's lines are its proposal or the caller's lines checked as
for one plot, with field names under `split.parts[i].` (`split.parts[i].lines`, `split.parts[i].lines[j].amount`, ...);
each part's remainder is placed automatically on its plot, but only when the whole split is valid (parts add up, every
part's lines valid). A preview answers `SPLIT_TOTAL_MISMATCH` and the parts' line problems in `problems`.
A save first locks the plots of the parts `FOR SHARE` in one call (`PlotCatalog.findAllAndLockShared`; a plot archived
or turned into common land since is 409 as for one plot), then all their plot ledger locks in one sorted call, and only
then reads the ledgers, so the debts, the proposal and the digest are those of the locked plots. It books one month,
takes **one** receipt number for a cash split, and writes one payment per part above zero: the same `group_id` (the id
of the first part), the receipt number on every part, the payer `split.payerMemberId`, the bank reference, and the payer
note sealed per payment (its own `FieldRef`). One `PAYMENT_RECORDED` per part (about its plot) and one
`PaymentsChanged` with every plot of the group. The card of a part lists the other parts (`groupParts`: id, plot,
amount, reversed).

**Reversal, reallocation and prepayment allocation** (feature 015 §2.5, §2.6, slice 4; `PaymentCorrections`). Each
write locks the plot `FOR SHARE` (`PlotCatalog.findAndLockShared`), then its plot ledger lock, and reads the plot's
ledger under it; a preview takes no lock and writes nothing.

- **Reverse**: the reason (`PaymentRules.reasonProblem`: 400 `reason` `REQUIRED`/`TOO_LONG`); the payment of the
  cooperative (404 `NOT_FOUND`); a reversal row or a payment reversed before is 409 `PAYMENT_ALREADY_REVERSED` (checked
  under the plot ledger lock; two reversals that still meet hit `payment_reversed_once_uq`, mapped to the same 409). An
  archived plot (or one turned into common land since) still has its payment reversed. The month is
  `BillingMonths.openForBooking(original.received_on)`: the payment's own month while it is open, otherwise the
  earliest open month (the 011 rule for charge reversals). `AllocationReleaser.releasePayment(..., PAYMENT_REVERSED,
  actor)` writes the releases, then `PaymentRecord.reversalOf` the reversal row (negated amount, same plot, source,
  group and `received_on`, the reason, no receipt number: the original keeps its own). A part of a split group is
  reversed alone. The released money is gone with the payment (no prepayment). Audit `PAYMENT_REVERSED`;
  `PaymentsChanged`. The controller answers with the card read after the commit.
- **Reallocate**: the payment (404; 409 `PAYMENT_ALREADY_REVERSED` for a reversal row or a reversed payment); the
  releases `ReleasePlanner.forPayment(..., REALLOCATED)` are planned and the state taken as if they were written
  (`PlotLedgerState.afterReleases`, pure); the digest is `PaymentDigest.reallocation` (the payment, its live
  allocations `id:remaining`, the open charges as if released, the lines a save writes; 409 `PREVIEW_OUTDATED`); the
  lines are the proposal of the payment's amount or the caller's, checked as at entry against that state
  (`LinePlacement`; with the setting off an empty list takes everything back as the payment's prepayment, with it on
  the remainder is placed automatically, so an empty list places everything again in the planner's order). A save
  calls `AllocationReleaser.releasePayment(..., REALLOCATED, actor)` and writes the lines with origin `REALLOCATION`
  (by the actor) and the automatic ones with origin `PREPAYMENT_AUTO` (by the system). Audit `PAYMENT_REALLOCATED`
  with the old lines and the caller's new lines (the automatic ones are ledger rows only); `PaymentsChanged`.
- **Allocate prepayment** ("Розподілити аванс"; meant for automatic allocation off, where the web offers it by
  `prepaymentAllocatable`; it still works while it is on and then places nothing automatically): the plot of the cooperative
  (404 `PLOT_NOT_FOUND`; archived plots and common land are not refused: the money is already there); an empty list of
  lines is 400 `lines` `REQUIRED`; a plot without prepayment (`PlotLedgerState.prepayment`) or without an open charge
  is 409 `NOTHING_TO_ALLOCATE` (a state, like `NOTHING_TO_RECALCULATE`, not a field of the request); the digest is
  `PaymentDigest.prepayment`; the lines are the proposal of the prepayment or the caller's, checked against the
  prepayment (`ALLOCATION_EXCEEDS_PAYMENT` when they exceed it). Each line's money comes from the payments that still
  have money left, oldest first (`AllocationPlanner.pairWithPayments`), so a line may become several allocation rows
  (origin `PREPAYMENT_MANUAL`, `created_by` the actor). Audit `PREPAYMENT_ALLOCATED`; `PaymentsChanged`.

The card (`GET /payments/{id}`) says what the viewer may do now: `reversible` (`PAYMENTS_REVERSE` and not reversed),
`reallocatable` (`PAYMENTS_ENTER` and not reversed) and `prepaymentAllocatable` (for the payment's plot, as on the
account). Its `currentAllocations` ("Зараз зараховано") are computed on the server (`PaymentViews.net`) from the
payment's rows; its `allocations` are the history ("Історія розподілу").

**Releases** (`AllocationReleaser`, `MANDATORY`): the one writer of release rows. `settleCharges(tenantId, plotIds,
createdBy)` releases what the charges of the plots no longer hold after a reversal or adjustment written in the same
transaction (`ReleasePlanner.forCharges`: all of a reversed charge, `CHARGE_REVERSED`; the excess of a lowered one from
its latest allocations, `CHARGE_ADJUSTED`); `releasePayment(tenantId, plotId, paymentId, cause, createdBy)` every live
allocation of one payment. Both assert the plot ledger locks (`PlotLedgerLock.assertHeld`: a missing lock is a bug and
an `IllegalStateException`), flush, read the plots' ledger again, write and flush the releases (so the caller's next
row comes after them) and return them. Rule: **a path that reverses or lowers a charge locks the plots and calls
`settleCharges`, and a path that reverses or reallocates a payment locks the plot and calls `releasePayment`, in its own
transaction before it commits**; the deferred backstops of V27 (`allocation_charge_cap`, `allocation_payment_cap`)
refuse a commit that forgot it. The releases carry `created_by` = the actor of the path (null for the system). The
charge paths (feature 015 slice 5), each releasing everything on a reversed charge (`CHARGE_REVERSED`) and the excess
of a lowered one from its latest allocations (`CHARGE_ADJUSTED`):

| Path | Plot ledger locks | `settleCharges` |
|---|---|---|
| `Reversals.reverse` (by hand) | the charge's plot, after the charging lock and the charge, before the month `FOR SHARE` | after the reversal row is flushed |
| `LossesRuns.reverse`, `CommonConsumerRuns.reverse` | every plot the run charged (`ChargeRepository.plotIdsOfRuns`), after the run lock and the run `FOR UPDATE`, before the month `FOR SHARE` | after `reverseLocked`, over the plots of the reversed charges |
| `RecalculationService.book` | step 0: the plots of the lines and every plot a run reversed as a whole charged, before the first month is booked in (the plan already holds every month `FOR SHARE`: those shared locks never conflict with the shared month locks of the paths that hold plot ledger locks, so the order cannot deadlock) | after the reversals, new charges and adjustments (step 4a) |

Each of them then publishes `ChargesBooked` for the plots (a recalculation also for the plots of the runs it reversed
as a whole), so the released money is placed again by the automatic allocation below when it is on. Paths that only
add charges (the month run, retries, readings, runs, opening balances and their import) take no plot ledger lock.

**Lock order** (technical considerations 2.4): charging lock (paths that charge) -> network / queue / charge type /
tariff `FOR SHARE` -> plot `FOR SHARE` (`PlotCatalog.findAndLockShared`) -> **plot ledger locks** (`PlotLedgerLock`:
`pg_advisory_xact_lock(hashtextextended('ledger.plot:'||tenant||':'||plot, 0))`, all plots of a path in one call in
sorted order, remembered in a transaction-bound set for `assertHeld`) -> billing month `FOR SHARE` -> the
`receipt_counter` row. The plot ledger lock stands for "the plot locked `FOR UPDATE` in the ledger" without a row lock
on the plots module's table (which booking paths hold `FOR SHARE`). Paths that place money on charges or take it back
lock the plots (payments, reversals and reallocations of payments, manual and automatic allocation of prepayment, and
the charge paths above); paths that only add charges do not. Writers that meet on a plot wait on its lock and then read
its ledger afresh (`AllocationConcurrencyTests`).

**Automatic allocation of prepayment** (`PrepaymentAllocator`, feature 015 §2.4, FS 2.7). With the cooperative's
setting `autoAllocatePrepayment` on (`settings.CooperativeSettings.autoAllocatePrepayment()`, default on), a plot's
prepayment is placed on its open charges whenever charges are booked for it: the allocator listens to `ChargesBooked`
with the ADR 0007 pattern (`@Async @TransactionalEventListener` after the commit, `TenantContext.runAs`, its own
transaction through a `TransactionTemplate`). In that transaction: the setting (off: nothing); the plots of the event
with a prepayment and an open charge, read without a lock; their plot ledger locks in one sorted call; their ledgers
read again; per plot `AllocationPlanner.propose` of the prepayment (never more than its payments have left) over the
open charges in the planner's order, each line's money taken from the payments with money left, oldest first
(`pairWithPayments`); allocations of origin `PREPAYMENT_AUTO` with `created_by` null (the account shows them as
"allocated from prepayment"); `PaymentsChanged` for the plots that got allocations (the plot status). Idempotent: only
the prepayment found under the lock is placed, so a redelivered event writes nothing. It never publishes
`ChargesBooked` (no loop). Payment entry, reallocation and manual allocation publish only `PaymentsChanged` and never
trigger it: entry and reallocation place their own remainder in their transaction (Automatic placement of a remainder
above), so what an entry leaves waits here only when the plot owed nothing more. Money released by a charge path becomes prepayment and is
placed by the `ChargesBooked` that path publishes (example 6: membership 600.00 reversed, 150.00 moves to the open
electricity, 450.00 stays). Turning the setting on places nothing at once (no sweep): a plot's prepayment is placed at
its next booking. Logs counts only; automatic allocations are ledger rows, not audit entries.

**Receipt numbers** (`ReceiptCounter`, `MANDATORY`): inside the saving transaction, after the plot ledger lock and the
month, `INSERT ... ON CONFLICT DO NOTHING` of `(tenant, 0)` and `UPDATE ... SET last_number = last_number + 1 RETURNING`.
The row lock serialises concurrent cash saves of the cooperative (no duplicates); a rollback undoes the step (no gaps).
Bank payments take no number. A cash split takes one number for the whole group, stored on every part (the gist
exclusion `payment_receipt_group_excl` keeps a number to one group).

**Payer note** (`PaymentNoteCipher`): sealed with `privacy.PersonalDataCipher` at `FieldRef("payment", "payer_note",
paymentId)` (the id is chosen before the row), opened only for the card of a viewer with `MEMBERS_VIEW`; sealed again
for the audit entry; never logged.

## Readings in use

`LedgerReadingUsage` implements `meters.ReadingUsage`: a charge reads its meter on the closed range of days from its
first to its last `meter_value_use` row (an interval charge: from its previous point to its reading); the readings of a
span are used when such a range of a charge that is not reversed meets the span. The meters module asks about the days
a change on a date reaches, from the day after the previous point to the day before the next one (or the date itself),
so a correction or deletion of a charged reading and a reading inside a charged interval are 409 `READING_IN_USE`, a
new reading after a charged interval is accepted, and after the reversal of the charge the reading can change again.
A booked losses run reads every meter of its charged nodes on the closed range of its `meter_value_use` rows (with
`run_id`), and a booked common consumer run the meters of the consumers it charged; the rows of a reversed run no
longer count. An adjustment of an interval writes the two `meter_value_use` rows of the expected interval, so its
readings stay used. It replaced the stub `meters/internal/NoChargesYetUsage`. A used reading is changed only together
with a recalculation (`READING_*` causes, `meters.ReadingChanges`, which skips this check).

## Tariff versions in use

`LedgerTariffVersionUsage` implements `tariffs.TariffVersionUsage`: a version is used while a charge that is not
reversed has a `charge_part` priced by it, so its correction or deletion is 409 `TARIFF_VERSION_IN_USE`; after the
reversal of those charges it is free again. An adjustment writes the parts of its expected charge, so those versions
count as used too. It replaces the stub `tariffs/internal/NoChargesYetUsage`. A used version is changed only together
with a recalculation (`TARIFF_VERSION_*` causes, `tariffs.TariffVersionChanges`, which skips this check).

## Plot status

`PlotStatusRefresher` listens to `ChargesBooked` and `PaymentsChanged` with the ADR 0007 pattern (`@Async
@TransactionalEventListener`, no `@Transactional`; `TenantContext.runAs(event.tenantId())`, then a
`TransactionTemplate`): `plots.PlotDebtCache.lockAll` (the cache rows `FOR UPDATE` in id order), then the committed
charges, allocations and payments of the plots (`PlotLedgerQueries.load`, one `UNION ALL` statement per 500 plots)
summed by `DebtCalculator.of(charges, allocations, payments)`, then `record(plot, debt, oldestAccrual, prepayment)` per
plot: the debt after payments, the oldest charge with something still open, and the prepayment (a plot without debt
is GREEN, also with a prepayment). Idempotent (it recomputes from the ledger), so a redelivered event writes
the same values; logs counts only. The colour itself is derived by `plots` when it is read (see the plots README), so a
change of the red threshold needs no refresh. Tests: `PlotStatusRefreshTests` (they wait for the asynchronous listener).

## Plot guard

`LedgerPlotGuard` implements `plots.PlotChangeGuard`: a plot that has any charge (reversed or not) is not deleted, 409
`PLOT_HAS_CHARGES` (the foreign key `charge_plot_fk` is the backstop); a plot that has payments but no charges is
refused with 409 `PLOT_HAS_PAYMENTS` (`payment_plot_fk`). Archiving and turning into common land are not
refused here. It is asked first among the plot guards (`@Order(100)`, before the guards of `meters`), so a plot with
charges and a meter is refused with `PLOT_HAS_CHARGES`.

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

Entity type `RECALCULATION` (`LedgerAuditPublisher.recalculationEvent`, published in one `AuditBatch`, after the
entry of the change that caused it), about the recalculation, label its months `from/to` (`YYYY-MM`):

- `RECALCULATION_BOOKED`: `cause` (the cause type, `MANUAL`, `TARIFF_VERSION_ADD`/`_UPDATE`/`_DELETE` or `READING_ENTER`/`_CORRECT`/`_DELETE`), `from`, `to`, `chargeTypes` (`base:<CODE>` or the stored
  name, joined by ", "; only when chosen), `plots` (plot numbers in natural order; only when chosen), `reason`, `count`
  (lines) and `total` (the sum of the differences). A tariff version cause puts the version change's own entry
  (`TARIFF_VERSION_ADDED`/`UPDATED`/`DELETED` of the tariffs module, old and new values) before it in the same batch,
  with the field `recalculationId` (new value: the recalculation's id); a reading cause the reading change's entry
  (`READING_ENTERED`/`REPLACED`/`CORRECTED`/`DELETED` of the meters module) the same way.

Entity type `PAYMENT` (`LedgerAuditPublisher.publishPayment`), about the plot like `CHARGE` (entity id the plot id,
label the plot number):

- `PAYMENT_RECORDED` (one per payment): `amount`, `source`, `receivedOn`, `billingMonth`, `receiptNumber` (cash) or
  `bankReference` (bank, when given), and `payerNote` sealed by the publisher at `FieldChange.newValueRef(eventId,
  "payerNote")` before the event exists (the 014 mechanism), so neither the outbox nor `audit_entry` holds it in the
  clear; the audit listing opens it only for a reader with `MEMBERS_VIEW`.
- `PAYMENT_REVERSED`: `amount` (the reversal, `-700.00`), `receiptNumber` (cash), `billingMonth` (where the reversal
  is booked), `reason`.
- `PAYMENT_REALLOCATED`: `amount`, `receivedOn`, `receiptNumber` (cash) and `lines` with the old and the new value,
  each line `<charge type code> <YYYY-MM of the service start> <amount>` (the stored name, user content, for a type
  without a code), joined by `; ` (`PaymentViews.auditLines`); a side without lines is absent.
- `PREPAYMENT_ALLOCATED`: `lines` (as above) and `total`.

Automatic allocations and releases are ledger rows, not audit entries.

Every action is a constant of `LedgerAuditPublisher.Action` and must also be a value of `AuditAction` in
`api/openapi.yaml`: the audit listing maps stored entries with `AuditAction.fromValue`, so a missing value breaks it
(guarded by `LedgerAuditActionsTests`).

## Demo data

The repeatable demo script `db/demo/R__demo_data.sql` has a block `-- BEGIN demo ledger` ... `-- END demo ledger`: one
PL/pgSQL `DO` block that books the demo cooperative's ledger as the application would have booked it. Its dates follow
the demo meters (the anchor is read back from plot 20's stored reading, as in the meters block): the anchor's month is
open, the month before it closed (September and October 2026 on the first load).

- **Settings:** losses mode `PROPORTIONAL`, the common consumers split `CONNECTED_PLOTS` by default and `EQUAL_PER_PLOT`
  for the street lighting, the red threshold 2 000,00 or 90 days (so the map shows red and yellow plots).
- **Months:** the closed month with its month run (membership and land tax; 59 charges on the first load), its
  reading charges and plot 12's opening balance of 1 500,00 ("Борг за 2025 рік", membership, its first day), closed by
  the chairman; the open month with its month run and its reading charges. Nothing is charged before the closed month.
- **Electricity by readings:** one `CONSUMPTION` charge per interval of a plot meter that ends in the closed month or
  later (keys, parts, readings and value uses as `IntervalCharging` writes them). Plot 25's meter (installed
  15.12.2025, before the first electricity tariff version) has one interval that cannot be charged: the open
  `INTERVAL` item "Немає тарифу на всі дні" of plot 25.
- **The losses run** over the latest round of readings (the 19th to the 5th day before the anchor): quarter А charged
  (losses of about 6.5 % of its consumption, one `LOSSES` charge per plot of Садова street), quarter Б `UNKNOWN` with
  the warning `UNKNOWN_VALUE` (plot 20's meter has no reading after 75 days before the anchor) and its open
  `LOSSES_NODE` item.
  The substation is not part of the run (it would be `SKIPPED_ABOVE` and queued too).
- **The common consumers run** over the same days: "Освітлення вул. Вишнева" equally among the 30 chargeable plots and
  "Свердловина №1" among the 27 connected plots by plot-days (plot 19 connected for 8 of the 15 days), at the
  "Спільні споживачі за лічильником" tariff the block creates (4.3200 per kWh).
- **Water connections:** every plot to the well since 01.05.2020 except 12а, 27 and 29; plot 19 from the 12th day
  before the anchor.
- **One reversal:** plot 14's membership of the open month, "Помилкове нарахування".
- **One recalculation** (feature 012), as a manual one books it: plot 2's membership was charged 30,00 in both months
  (a rate of 60 per sotka) and is 25,00 now. The closed month's charge gets an adjustment of −5,00 booked in the open
  month (its service period and debt date kept), the open month's 30,00 is reversed (source `RECALCULATION`) and 25,00
  charged again under the `#r` key; two `recalculation_line` rows (`ADJUST`, `REVERSE_AND_RECHARGE`). The cleanup
  removes the lines, the recalculations and the adjustments first.
- **The plot status cache:** every plot's debt and oldest debt date, and the colour as the refresher would record it.

So "Не нараховано" shows two items: exactly one "Немає тарифу на всі дні" (plot 25) and quarter Б's unknown value.
Re-runnable: the block first deletes the demo cooperative's delivery rows and other feature 017 rows (`delivery`
points at invoices and payments; `notifications.internal.DemoNotifications` writes them again on the next start), then
every ledger row of the demo cooperative (as `nadel_owner` with `app.ledger_maintenance = 'on'`), its water
connections and common consumer settings, then books everything again; a
change made to the demo ledger by hand is lost on a re-run. It assumes one version per demo tariff and real readings
at both ends of the runs (it raises an exception otherwise).

It runs only when the Flyway placeholder `demoledger` is `on` (`application.yml`, dev profile). Integration tests set it
`off` (`IntegrationTest`), so the demo cooperative the tests share has no charges, debts, water connections or ledger
settings; `DemoLedgerData.restore` loads the block on purpose and `DemoLedgerData.clear` removes it (and
`DemoMeterData.clear` and `DemoTariffData.clear` call it first). `DemoLedgerDataTests` recalculates every demo charge
with the ledger's own planning code (`TariffMonthPlanning`, `ReadingChargePlanning`, `LossesCalculator` with
`LossesPlanning`, `CommonConsumerPlanning`) and compares amounts, parts, the stored calculation, value uses, run rows
and queue items; republishes `ChargesBooked` and checks the status refresher records the same debts; and checks the
screens (months, the queue, the map, accounts) and that the block is re-runnable.

## Dependencies

`tariffs` (the read facade `ChargeTypeCatalog`: `find`, `findAndLockShared`, `all`, `findByNames`; `TariffBook`: `snapshot`,
`tariffIds`, `lockShared`; the pure `TariffResolver`, `ChargeCalculator.breakdown` and `DaySet`; the port
`TariffVersionUsage`, implemented here; the facade `TariffVersionChanges`), `plots` (`PlotCatalog`: `find`, `findAll`, `findAndLockShared`,
`inNaturalOrder`, `findChargeable`, `findActiveByNumbers`, `findAllAndLockShared`; the SPI `PlotChangeGuard`; the debt
cache writer `PlotDebtCache`; the import records `PlotImportRow`, `PlotImportPlan`),
`meters` (`MeterNetwork.staleMeters`, `lockSharedPoints`, `network` and `lockSharedNetwork`, the event
`PlotMeterPointsChanged`, `ConsumptionCalculator`, `Quantity`, `MeterValue`, `WaterConnections.connectedDays` and
`connectedToSource`, `MeterCatalog.find`, `chainOf` and `plotMeterIds`; the port `ReadingUsage`, implemented here;
the facade `ReadingChanges`), `settings` (`CooperativeSettings.lossesMode`, `lossesPercent`,
`commonConsumerDefaultSplit`, `autoAllocatePrepayment`; `LossesMode`, `CommonConsumerSplit`), `money` (`Money`), `identity` (`CurrentUser`, `AccessProfile`, `Permission`,
`OwnedPlotsSource`), `audit` (events, `ActorNames`, `FieldChange`), `members` (`MemberDirectory.find`: the payer of a
payment), `privacy` (`PersonalDataCipher`, `FieldRef`: the payer note), `problem`, `tenancy` (`TenantContext`). `imports` depends on
`ledger` (`OpeningBalanceImport`) and `reports` reads it through `LedgerReadModel`.

## Permissions

`CHARGES_VIEW` (Chairman, Treasurer): every plot's account, the months, the queue, the losses runs and the common
consumer runs. `CHARGES_RUN` (Chairman): opening balances, the month run (and its preview), retries, losses runs and
common consumer runs (and their previews). `CHARGES_REVERSE` (Chairman): reversing a charge, a losses run or a common
consumer run. `SETTINGS_MANAGE`: reading and changing the split and service users of a common consumer. `PERIODS_CLOSE` (Chairman): closing a month. `RECALCULATION_RUN` (Chairman, V19): previewing and booking a recalculation; reading the recalculations needs `CHARGES_VIEW`. `PAYMENTS_VIEW` (Chairman, Treasurer, V27): the payments, their allocations and the payments report, and the payments on every plot account (a member sees those of the plots they own now without it). `PAYMENTS_ENTER` (Chairman, Treasurer, V27): entering, splitting and reallocating payments, allocating prepayment. `PAYMENTS_REVERSE` (Chairman, V27): reversing a payment. Defined in
`identity.Permission`, seeded by `seed_base_roles` (V13, V19, V27; existing cooperatives by targeted inserts).

## Tests

`BillingCalendarTests`, `DebtCalculatorTests` (pure, adjustments included), `ChargeRecordTests` (pure: the adjustment
and the reversal of a recalculation); `RecalculationQueriesTests` (the list newest first and paged, a record with its
scope, lines and totals, the effective amount, 404 for another cooperative, who may read and run them, a preview with
no lines and 409 `NOTHING_TO_RECALCULATE`); `ManualRecalculationTests` (area 600 -> 700 with the previous month closed
and the current one open end to end: +4.17 adjustment in the open month with the closed month's service period and
date, reversal -25.00 and new 29.17 under `#r`, the account, the closed month unchanged, the record and its lines, the
audit entry; a new rate from the open month; a second run with no changes and 409; a stale preview 409 with nothing
written; a hand reversal stays; reason required and at most 500; future month, `to` before `from`, missing scope,
another cause type; more than 24 months; unknown, archived and common land plots, unknown and inactive types; who may
preview and book, and another cooperative); `RecalculationPlannerTests`, `ChargeIdentityTests` and
`RecalculationDigestTests` (pure: every row of the requirement §3, identities, frozen chains, the effective amount,
the digest); `IntervalRecalculationTests` (intervals and fixed losses after a tariff version change, open and closed,
a queued interval charged and resolved, water days); `RunRecalculationTests` (losses and common consumer runs: open
reversed and booked again, closed per-plot adjustments, a plot new to a closed run); `TariffRecalculationTests` (the
tariff version causes) and `ReadingRecalculationTests` (the reading causes, no duplicate from the listener, the
electrician refused); `RecalculationTenantIsolationTests` (V19 tables and charge rules);
`MonthRunTests` (the month-run rows of §3: preview, booking,
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
closed month, an unknown consumer queued and retried, an item narrowed by a run or a retry over some of its days and
resolved by a run over the rest, the readings a run uses, validation, consumer selection, the settings of a consumer
with validation and audit, the settings of a replaced consumer read and changed through its new meter and charged by a
run, permissions), `CommonConsumerTenantIsolationTests` (V18), the water
rows of `MonthRunTests` (25.00 for
a connected plot, nothing and nothing queued for one that is not, 12.90 from the 16th, a conflict only for a connected
plot) and of `ReadingChargesTests` (a plot not connected for the whole interval: nothing and nothing queued; the
connected days only),
`TariffConflictWarningTests` (the tariffs page), `NotChargedTenantIsolationTests` (V15) - these run in a cooperative of
their own (`FreshCooperativeSupport`); `DemoLedgerDataTests` (the demo ledger, see Demo data); `BillingMonthsTests` (the list and totals, the month page and its
check list, closing in order, a closed month that gets nothing more, late bookings, future months, permissions, audit,
the `billing_month_guard` trigger; `received` netting a payment reversal, and a late payment of a closed month received
in the open month it is booked in); `PlotStatusRefreshTests`; `LedgerAccountTests` (the account, debts per type, the reversal
rules of §3, opening balances, booking after a closed month, the plot guard, allowed and denied per endpoint for the
chairman, the treasurer, custom roles, a member for their own and another plot, the other cooperative and the platform
operator; adjustments: the debt, `adjusts`/`adjustedBy`, `recalculationId` only with `CHARGES_VIEW`, the `kind` filter,
409 `ADJUSTMENT_NOT_REVERSIBLE`; payments (feature 015): payments and charges with their dates newest first, `paid` and
`open` per charge, the debt after payments, the prepayment, an adjustment's effect on what is open, a reversed payment
next to its reversal with its release, the placements of the prepayment by hand and by the system, the `PAYMENTS`
filter keeping the debts, a member's own plot with receipt numbers but no payer note or author, the board as its
permissions allow); `PaymentsPermissionTests` (every payments endpoint - list, card, entry preview and save, reversal,
reallocation preview and save, prepayment allocation preview and save - for the chairman, the treasurer (no reversal),
a member with their own and another plot, the electrician, a chairman and a member of another cooperative (404 and
403), the operator and no token, nothing written when refused; the payments on the account per viewer); `LedgerAuditTests` (entries and `ChargesBooked`); `LedgerTenantIsolationTests` and `LedgerAppendOnlyTests`
(the tables); `LedgerMigrationTests` (V13 on existing cooperatives); `ChargesBookedTests`. Payments (feature 015):
`PaymentQueriesTests` (the list newest first without reversal rows, each filter alone and together, pagination; in a
cooperative of its own), `PaymentEntryTests` (examples 1, 2, 10 with automatic allocation off, 11, 16, 18 and 19 end to end - the remainder
placed at once with `resultLines`, `PREPAYMENT_AUTO` rows by the system and the card's `currentAllocations` -, a
save with other lines than its preview is stale, a preview with every kind of line problem answering 200 with the
whole table, an empty allocation, line errors refused by a save, common land,
archived, the values, unknown and foreign plots, a stale digest writing nothing, a preview writing nothing, the list
and the card with the payer note only for `MEMBERS_VIEW`, permissions, the plot guard),
`ReceiptNumberConcurrencyTests` (example 17 with both saves waiting on the counter row, a failed save leaves no gap, a
bank payment takes no number, a reversed number is not reused), `PaymentSplitTests` (examples 12, 13, 14 and 15 end to
end: the payer's plots with debts and proposed parts, parts without amounts, one group and one receipt number for a
cash split with the payer and a sealed note on every part, the card's other parts and `groupSize` in the list; a bank
split without a number, a mismatch writing nothing, a plot of another member, twice or archived, nothing owed -> all to
the first plot and a `0.00` part not saved, per-part line errors (400 on save, `problems` with the part's plot in a
preview, `SPLIT_TOTAL_MISMATCH` in a preview) and lines, a part's remainder placed at once (and waiting with the
setting off), the request checks, a stale digest,
permissions), `AllocationConcurrencyTests` (two entries on one plot, and a split with a payment of one of its plots, in
real concurrent transactions: the second waits on the plot ledger lock while the first holds the receipt counter, then
reads the ledger afresh, so no charge and no payment is ever over-allocated; without the lock the deferred backstop
refuses one at commit; an entry and the automatic allocation on one plot, and an entry and a recalculation whose
-100.00 adjustment lowers the paid charge: the allocator or the recalculation waits on the entry's plot ledger lock,
then the allocator places only what is left open and the adjustment releases 100.00 of the entry's latest allocation;
no deadlock), `AutoReleaseTests` (example 6 by hand with automatic allocation on, with the card's net lines after the
charge reversal, and off; an unpaid charge reversed
without releases; the reversal of a losses run and of a common consumer run releasing what was paid on their charges;
a recalculation that reverses and charges again in an open month, the prepayment then paying the new charge; example 7
releasing 100.00 of the latest allocation, `CHARGE_ADJUSTED`; example 8 leaving 50.00 open and the plot no longer
green; every path commits), `AutoAllocationTests` (examples 4 and 5 after a month run; a month run over plots with,
partly with and without a prepayment, a prepayment of two payments taken oldest first; an opening balance; a
redelivered `ChargesBooked` writing nothing; an entry placing its own remainder at once and only what no charge takes
waiting, with the setting off all of it waiting; switching it on places nothing
until the next booking; `PREPAYMENT_AUTO` by the system) - these run in a cooperative of their own
(`FreshPaymentSupport`), `PaymentAuditTests` (`PAYMENT_RECORDED` values, the
sealed note: not readable in `payment`, `audit_entry`, the outbox or the debug log; `PAYMENT_REALLOCATED` with the old
and new lines, `PAYMENT_REVERSED` with the reason, `PREPAYMENT_ALLOCATED` with lines and total, all read back by the
audit listing), `PaymentReversalTests` (example 9: releases then the reversal row, the debts back; the card's
`reversible`/`reallocatable`; reversed once, a reversal row refused; the reason; `PAYMENTS_REVERSE` only, another
cooperative 404; a reversed cash payment keeps its receipt in list and card and the next one takes the next number; a
split part alone; the payment's month while open, otherwise the earliest open month; an archived plot; the prepayment
down by what was left of the payment, never below zero), `ReallocationTests` (the FS 2.6 example with releases and new
allocations on the card; the proposal as if released; an empty list with the setting off, and on (everything placed
again); a stale digest writing nothing, also for other lines than the preview's; a preview with problems; lines that
leave a remainder placed in full with the card's net lines and authors; a reversed payment and a reversal row; lines
above the payment or a charge or not of the plot; permissions),
`PrepaymentAllocationTests` (example 5 with automatic allocation off, then land tax paid and 30.00 left; a prepayment
of two payments taken oldest first (with the setting on); `NOTHING_TO_ALLOCATE`, an empty list, lines above the
prepayment; a stale digest; `prepaymentAllocatable` on the account and the card per viewer, setting, prepayment and open
charge; permissions and plots of another cooperative; the class runs with automatic allocation off), `AllocationReleaserTests` (`settleCharges` after a charge reversal in
the same transaction, by the system; a path without it refused at commit by `allocation_charge_cap`; no plot ledger
lock -> `IllegalStateException`), the payment cases of
`PlotStatusRefreshTests` (red to yellow, green with a prepayment, the oldest unpaid charge, a redelivered
`PaymentsChanged`, the cache equal to an SQL rebuild); `PaymentTenantIsolationTests`, `PaymentAppendOnlyTests` (V27) and
the pure payment tests listed under Rules. Test helpers clear ledger rows
and billing months as `nadel_owner` with the maintenance setting (`LedgerTestSupport`). The opening balances sheet is
tested in `imports` (`ImportOpeningBalancesTests`: both languages, row problems, booking, a re-import of the same file,
a reversed balance imported again, a renamed seeded type, a stale preview).
