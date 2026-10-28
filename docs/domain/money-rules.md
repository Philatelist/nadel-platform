# Money rules

This document is the source of truth for the `tariffs`, `meters`, `ledger`, `payments` and `documents` modules.
Any rule change is made here first.

## 1. Representing amounts

- Currency is the hryvnia (UAH), 2 decimal places.
- Java: `BigDecimal` through the `Money` value object (module `app.nadel.money`). DB: `numeric(14,2)`. JSON: string `"1250.50"`.
- Rounding is `HALF_UP` to kopecks, done only by the `Money` value object (`Money.of`, `Money.ofQuotient`) and applied only when an amount is produced (a charge, or the expected amount of a plot), never in intermediate calculations: those stay exact `BigDecimal`s.
- A proportional split (one payment over several plots by their debts, feature 015) rounds in one place too, `Money.allocate(weights)`: each part is its exact share rounded down to the kopeck, the leftover kopecks go to the largest remainders, ties to the earlier weight, so the parts add up to the amount (100.00 over three equal debts → 33.34, 33.33, 33.33; 200.00 over 300.00 and 100.00 → 150.00 and 50.00). With every weight zero the whole amount is the first part (ADR 0013).
- A tariff rate is not money. It is the `Rate` value object (module `app.nadel.money`): not negative, at most 10 integer digits and at most 4 decimals, held with scale 4 (`numeric(14,4)`, JSON string `"4.3200"`). A rate is never rounded, not even to 4 places: a fifth decimal is rejected at the boundary, never cut.

## 2. Balance

A plot's balance is never stored as a figure of its own. It is calculated from the ledger (the cached copy in
`plot_status` can be rebuilt at any time):

```
open amount of a charge = effective amount (the charge plus its adjustments, 5.4; 0.00 once reversed) − its allocations (net of releases)
debt per charge type    = Σ open amounts of the plot's charges of that type
plot debt               = Σ debts per charge type
prepayment              = Σ payments (net of reversals) − Σ allocations (net of releases), never negative
```

- Only `REGULAR` and `OPENING` charges that are not reversed carry an open amount. Reversals and adjustments are not
  allocated to; they change the effective amount of their original charge (feature 011, 012).
- A plot with payments and allocations: the debt of a type falls by what is allocated to its charges, and the money not
  allocated is the prepayment. A plot with no payments has no allocations, so its debt is the sum of its effective
  amounts.
- Example: membership 600.00 (January) and electricity 250.00 (February), a payment of 700.00 allocated as 600.00 and
  100.00: open amounts 0.00 and 150.00, debts 0.00 (membership) and 150.00 (electricity), plot debt 150.00,
  prepayment 0.00. With a payment of 1000.00: both charges are paid in full and the prepayment is 150.00.

## 3. Ledger

- `charge`, `payment` and `allocation` are append-only. For `charge` and its parts (`charge_part`) this is enforced by
  the database (feature 011, ADR 0006): the application role has no `UPDATE`, `DELETE` or `TRUNCATE`, and a trigger
  refuses them to every role; the only way past it is the owner role with the transaction setting
  `app.ledger_maintenance = 'on'`, used by demo data and test helpers, never by the application.
- **Kinds.** `REGULAR` (an accrual), `REVERSAL`, `OPENING` (an opening balance, 3.2) and `ADJUSTMENT` (a correction of
  a charge of a closed month booked by a recalculation, 5.4). A wrong charge is cancelled by a `REVERSAL`: the same
  plot, charge type, service period and debt date, the opposite amount, the original named and a mandatory reason. A
  charge is reversed at most once, and a reversal or an adjustment is never reversed. A charge booked in a closed month
  is not reversed by hand: its correction is an adjustment in the earliest open month, booked by a recalculation
  (section 5). A whole losses or common consumers run is reversed in one step (6.6, 6.7) while its month is open.
- **Sources.** `TARIFF` (the month run, 4.1), `CONSUMPTION` (an interval of a plot meter, 6.5), `LOSSES` (the
  fixed-percentage losses of an interval, 6.5, or a proportional losses run, 6.6), `COMMON_CONSUMER` (a common
  consumers run, 6.7), `OPENING` (3.2), `MANUAL_REVERSAL` (a reversal by hand or of a run), `RECALCULATION` (an
  adjustment, or a reversal booked by a recalculation, section 5).
- Every entry stores its amount, its **service period** (the days it pays for), its **debt date** (`accrued_on`, the day
  the debt counts from for the plot status, section 9), the billing month it is booked in, its author (none for the
  charge of a reading, which follows the approval of the reading), its timestamp, the reason of a manual operation and
  how it was calculated (codes and numbers only: tariff versions, sotkas or kWh, rates, days, readings, the node or
  consumer of a run). Debt dates: the first day of the billing month for a month-run charge (a one-time fee: its due
  date), the reading date for a charge of a reading, the last day of the range for a run, the given date for an opening
  balance, the debt date of the original charge for an adjustment.
- One charge per source and case (a source key: a tariff, plot and month; the two points of an interval; a run and a
  plot), so a rerun, a redelivered event or a retry never charges twice, and a charge reversed by hand is not
  re-created by a rerun, a retry or a recalculation (5.1). A charge a recalculation set aside because the case can no
  longer be charged (reversed, or adjusted to 0.00 in a closed month; the case is queued) is charged again by a rerun
  or a retry once it can be, once, under a revision key (5.5).

### 3.1 Billing months and closing

- Every charge is booked in a billing month, which is open or closed. The months of a cooperative run from its first
  month to the current one (a month without charges is open); a month after the current one is never charged by a run
  and never closed.
- Months close only in order (every earlier month first); a closed month is never reopened and nothing is booked in it
  any more. A charge about a day of a closed month is booked in the earliest open month after it (a new month when
  there is none), keeping its own service period and debt date, and shows "за <period>, нараховано в <month>".
- The closing check list (month run done, "not charged" items of the month, plot meters without a reading for more than
  60 days) never blocks closing; the person closing confirms with the list in view.

### 3.2 Opening balances

- To start with a cooperative that already has debts, the board enters an opening debt per plot and charge type as of a
  date (the go-live date), with an optional reason or document reference: a charge of kind and source `OPENING`. The
  amount is above zero (at most two decimals, never rounded) and the date not in the future; it is booked in the month
  of its date or the earliest open month after it, and its debt counts from that date.
- Entered one by one on the plot account or in bulk with the import sheet "Вхідне сальдо" / "Opening balances" (feature
  008 rules: a preview first, every row checked, both languages). Importing the same file again books nothing twice.
- Example: 1 500.00 for membership on plot 12 as of 01.09.2026, "Борг за 2025 рік" → the membership debt rises by
  1 500.00 and, above the red amount of 1 000.00, the plot turns red (section 9).

### 3.3 The "not charged" queue

- Everything the app could not charge is an item with its reason and a link to fix it: a month-run conflict (two
  tariffs, `CONFLICTING_TARIFFS`, 4.1); a reading interval with no tariff for every day (`NO_TARIFF_FOR_ALL_DAYS`),
  conflicting tariffs, a plot outside the scope of every electricity tariff (`OUTSIDE_SCOPE`) or excluded (`EXCLUDED`)
  (6.5); a node of a losses run whose value is unknown (`UNKNOWN_NODE_VALUE`, 6.6); a common consumer whose value is
  unknown (`UNKNOWN_CONSUMER_VALUE`, 6.7). A case is listed once (one open item per case, refreshed by a later attempt).
- What is owed nothing is not an item: no tariff of a recurring type for the plot, an amount of 0.00, an inactive charge
  type, a water meter of a plot not connected to water (section 11).
- **Retries.** "Повторити" retries one item. A booked month run also retries every open month item and every open
  reading interval of the cooperative, whatever its month. A losses node or a common consumer item is retried by
  "Повторити" (a run of that node or consumer alone over the item's days) and settled by a later run that charges its
  days (an item a run covers only partly is narrowed to the days left). An item disappears (`RESOLVED`) when a retry, a
  run or a recalculation (5.6) charges it, or the charge exists already; it becomes `OBSOLETE` when nothing is left to
  charge; otherwise it stays open with the reason of the latest attempt. A charge of a retry whose month was closed
  meanwhile is booked in the earliest open month with its own service period. Items never block a month from closing.

## 4. Tariffs

- A tariff belongs to a charge type (membership fee, electricity, land tax, water, or a custom special-purpose fee). A charge type may have several tariffs. A tariff has versions, each with `valid_from` (effective date). A version applies until the day before the next version's `valid_from`, or without end; the end date is derived, never stored. A change of rate is normally a new version, and two versions of a tariff never start on the same date. A version can be corrected or deleted until a charge uses it; after that a correction goes through recalculation (section 5). The only version of a tariff is never deleted.
- Calculation basis: `PER_PLOT`, `PER_AREA_SOTKA` (the rate per sotka, area in m² ÷ 100), `PER_KWH`, `PER_M3` (water, per cubic metre of a meter reading), `FIXED_ONE_TIME` (one-off special-purpose fee with a due date).
- Frequency: `MONTHLY`, `QUARTERLY`, `YEARLY`, `ONE_OFF`. `ONE_OFF` goes with `FIXED_ONE_TIME` and only with it, and a one-off version has a due date.
- Rate: a `Rate` with up to 4 decimals, never rounded on its own (section 1).
- Example: membership fee 50 UAH per sotka per year, plot of 6 sotkas, charged monthly → 50 × 6 / 12 = 25.00 UAH per month.

### 4.1 Expected amount of a plot for a month

The cooperative bills monthly. The amount one tariff charges one plot for a month is calculated once, in `tariffs.ChargeCalculator` (pure code, public), and the expected-amount preview (`GET /plots/{id}/tariffs`) and the ledger (feature 011) both call it.

```
units          PER_PLOT 1 · PER_AREA_SOTKA area_m2 / 100 (exact)
periods share  MONTHLY 1 · QUARTERLY 1/3 · YEARLY 1/12
recurring      rate × units × periods share × (days the version is valid in the month) / (days in the month)
FIXED_ONE_TIME its whole rate, in the month of its due date, if that version is valid on the due date; 0.00 in every other month
PER_KWH/PER_M3 not calculated here: needs readings (features 010–011); the plot shows "by readings"
```

- **Month split by days.** When a version changes inside the month, or the first version starts inside it, each version counts for the days it is valid divided by the days in the month `D` (30 in November, 29 in February 2028). A version that does not overlap the month contributes nothing. A `FIXED_ONE_TIME` fee is never split by days.
- **Not listed.** If no version of the tariff applies on any day of the month (the month is before the first version), the tariff is not listed for that month. A one-time fee whose version has started but whose due month is another month is listed with `0.00`.
- **One rounding.** Every contribution is kept exact and added as one fraction over the common denominator `D × 12` (rates are never divided); the sum is divided once and rounded `HALF_UP` to kopecks by `Money.ofQuotient`. Rounding each version separately would give a different amount and is wrong (rate 0.05 for 15 + 15 days of 30 is 0.05, not 0.03 + 0.03).
- **Meter-based tariffs.** A month in which any overlapping version is `PER_KWH` or `PER_M3` is "by readings" for the whole tariff, with no amount.
- **Per plot, not per owner.** The amount is per plot; the ownership share of a co-owner does not change it (section 8).
- **Month total** = the sum of the per-tariff amounts, each rounded once.
- **The ledger's month run (feature 011)** charges this amount through `ChargeCalculator.breakdown`, which counts only
  the days the tariff may charge the plot (every day, or for water the days the plot is connected): each recurring
  version counts for its valid **and** applicable days over `D`, a one-time fee only when its due date is applicable;
  one rounding as above. It stores the parts (version, days, sotkas or one plot, rate). An amount of `0.00` creates no
  charge. One charge per plot, tariff and month: a rerun never charges twice and never re-creates a charge reversed by
  hand.
- **Conflicting tariffs.** When two tariffs of the same charge type apply to one plot (its scope, not excluded) on the
  same day, neither is charged for that plot; the month run puts the case in the "not charged" queue naming both
  tariffs, and the tariffs page warns. Versions never end, so two such tariffs conflict from the later first version on.
  **Only tariffs that would charge the plot more than `0.00` in the month take part in a conflict:** the month run (and
  a retry) leaves out the tariffs whose amount for the plot and month is `0.00` or that are charged by readings; one
  tariff left is charged, two or more are the conflict (the queue item names those), none is nothing. So two
  `FIXED_ONE_TIME` fees of one charge type due in different months never conflict, and a one-time fee is charged in its
  due month (example: "Цільовий 2025" due 15.05.2025 and "Цільовий 2026" due 15.10.2026 on the same plot: the October
  2026 run charges 1 200.00 of the 2026 fee and queues nothing). A plot that already has the month's charge of one of
  the tariffs is not a conflict for that month either (nothing new, nothing queued). The tariffs page keeps warning about
  every overlap of scopes from today on, whatever the amounts.

Worked examples (each is a unit test of `ChargeCalculator`):

| Case | Input | Expected |
|---|---|---|
| Membership per sotka, yearly rate, monthly billing | 50.0000 UAH/sotka/year, plot 600 m² | 50 × 6 / 12 = 25.00 |
| Odd area | 50.0000 UAH/sotka/year, plot 612 m² | 50 × 6.12 / 12 = 25.50 |
| Rounding once at the end | 33.3333 UAH/sotka/year, plot 700 m² | 33.3333 × 7 / 12 = 19.444425 → 19.44 |
| Per plot, quarterly | 300.0000 UAH/plot/quarter | 100.00 per month |
| Version change mid-month (30-day month) | 50.0000 until the 15th, 60.0000 from the 16th, yearly per sotka, 600 m² | (50 × 6 / 12 × 15/30) + (60 × 6 / 12 × 15/30) = 12.50 + 15.00 = 27.50 |
| Special-purpose fee | FIXED_ONE_TIME 1 200.00, due 01.05.2026 | 1 200.00 in May 2026, 0.00 in other months |
| Excluded plot | ALL_PLOTS with plot 17 excluded | plot 17: no amount, reason shown |
| Land tax | 35.0000 UAH/sotka/year, plot 600 m² | 35 × 6 / 12 = 17.50 per month |
| Common land | any tariff | never applies |

### 4.2 Which plots a tariff applies to

- A tariff has a scope. `ALL_PLOTS` (the default for a new tariff) applies to every active plot of kind `PLOT`, minus the **exclusions**: plots the general meeting freed from the fee, each with a mandatory reason. `SELECTED_PLOTS` applies only to the listed plots. Exclusions exist only with `ALL_PLOTS`; switching the kind removes the rows of the other kind.
- **Common land never** has a tariff: it cannot be selected or excluded and gets no amount. **Archived plots** get nothing either.
- An excluded plot shows the tariff as not charged, with the reason and no amount, but only for months in which the tariff would otherwise be listed.
- Tariffs of an archived charge type never apply; tariffs of an inactive charge type give no expected amount until the type is active again.
- `PER_KWH` and `PER_M3` tariffs apply to plots with an active meter of that resource; until the meter registry arrives (010) they show "by readings" for every plot in scope.
- A member sees tariffs and expected amounts only for the plots they currently own; any other plot is "not found".

## 5. Retroactive recalculation

Feature 012 (module `ledger`: `RecalculationPlanner`, pure, and `RecalculationService`). When a tariff rate, a reading or
other data turns out to be wrong after charges were made, a recalculation corrects the past without ever changing a
booked charge: the app works out what every affected charge should be with the data as it is now, shows the
differences in a preview, and books them only when the chairman confirms with a reason. A recalculation is never
automatic.

### 5.1 The rule: expected against effective

- **Expected.** The charges 011 would book today with the current data, calculated by the 011 planners themselves (the
  month run of 4.1, the reading intervals with fixed-percentage losses of 6.5, the proportional losses runs of 6.6, the
  common consumers runs of 6.7) with every existing charge ignored. There is no second calculator. Each expected charge
  is rounded once, to kopecks, exactly as 011 rounds it.
- **Effective amount.** The booked charge plus all its adjustments (5.4). A reversed charge and its reversal add up to
  `0.00`, so a reversed charge is never compared again. The difference `expected − effective` is the difference of two
  rounded amounts: it is exact and never rounded again.
- **Identity.** A booked charge corresponds to an expected one when it charges the same thing:

| Charge | Identity |
|---|---|
| A recurring or one-time tariff fee (month run) | the tariff, the plot and the month of its source key (a `#r…` revision suffix ignored, 5.5) |
| An interval of a plot meter (consumption or fixed-percentage losses; electricity or water) | the source, the meter and the dates of the interval's two points. Not the reading ids: a corrected reading is a new reading with a new id, but the interval stays the same while its dates stay |
| A plot's line of a proportional losses run | the run and the plot |
| A plot's line of a common consumers run | the run, the consumer and the plot |

- Only a `REGULAR` charge has an identity. Its reversals and adjustments belong to its chain; **opening balances are
  never recalculated**.
- **What is booked for each identity:**

| Case | Month of the booked charge | What is booked |
|---|---|---|
| The amount differs | open | a `REVERSAL` of the booked charge (source `RECALCULATION`, the reason, in the booked charge's month) and a new `REGULAR` charge with the expected amount and calculation, booked in the month 011 books it in |
| The amount differs | closed | an `ADJUSTMENT` for `expected − effective` in the earliest open month, linked to the original charge, with its service period and debt date |
| The charge should no longer exist (its interval is gone, the tariff no longer applies, the expected amount is `0.00`, or it cannot be charged any more and is queued, 5.6) | open | a `REVERSAL` |
| The charge should no longer exist | closed | an `ADJUSTMENT` for `−effective` |
| An expected charge has no booked counterpart (a back-dated version now covers the days, an interval changed its points) | — | a new `REGULAR` charge, booked like any 011 charge: in its own month if open, else in the earliest open month with its own service period |
| Expected equals effective | — | nothing |

- **An interval whose points changed** (a reading deleted or entered inside it, or a reading moved to another date) is a
  different identity: the old interval's charges are reversed (open month) or cancelled by an adjustment `−effective`
  (closed month), and the new intervals are charged as new charges. The total can stay the same. A correction of a
  reading's value on the same date keeps both intervals around it: they are the same identities with new amounts.
- **A charge that now belongs to another plot or charge type** (a meter moved) is a charge that should no longer exist,
  and the expected one is a new charge.
- **Hand reversals stay.** A charge reversed by hand (`MANUAL_REVERSAL`) and not booked again is frozen: the
  recalculation leaves its chain alone. Reversals made by a recalculation have the source `RECALCULATION`, so the two
  are always told apart.
- **Closed months never change.** A closed month keeps its charges and totals; its corrections appear only as
  adjustments in an open month. A charge that adjustments brought to `0.00` stays in its closed month.
- **Effective never below zero.** The expected amount is never negative, so a recalculation brings an effective amount
  down to `0.00` at most. A correction that lowers a debt already paid releases the excess of the allocations and makes
  a prepayment of that plot (section 7), never a negative debt.

### 5.2 Losses and common consumers runs

- A run follows its own billing month (the month of its range end, or the earliest open month after it, 6.6), not the
  month a single charge of it is booked in.
- **Open month:** when any line of the run differs, every live charge of the run is reversed (source `RECALCULATION`,
  the run marked reversed) and the run is booked again as a new run for the same range and the same nodes or consumers,
  with the new numbers; the lines whose amount stays are reversed and booked again too. When nothing is left to charge,
  the run is only reversed.
- **Closed month:** the identity rules of 5.1 per plot line: an adjustment for the difference of each plot's share, and
  a plot new to the run gets a new charge of the original run in the earliest open month.

### 5.3 What starts a recalculation, and its scope

| Cause | Where | Scope |
|---|---|---|
| A tariff version added with a start in the past, or a used version corrected or deleted | the tariff card | the tariff's charge type; the chargeable plots the tariff covers; the billing months the old and new validity of the version touch (from the cooperative's first billing month to the current month); the interval charges and runs of that charge type that close from the first of those months on |
| A used reading corrected or deleted, or a reading entered inside a charged interval | the meter card, the reading round | the meter's intervals around the old and the new date (from the previous to the next point); the booked runs that read the meter on a day the change reaches (for electricity also the common consumers runs); no tariff fee |
| Anything else that changes past amounts: plot area, tariff scope or exclusions, water connection dates, the meter tree, a charge type made active again | "Нарахування → Перерахунок" (manual) | months from–to (never after the current month, at most 24 months), charge types (all active ones or the chosen), plots (all chargeable ones or the chosen); the booked runs whose range ends in the months |

- **A cause-driven change and its recalculation are one booking.** The version or reading changes only together with
  the recalculation; if the booking is refused, the change is not saved either. A change whose plan has no lines (it
  alters no amount) is saved alone, without a reason and without a recalculation record. Editing only the note of a
  used version is saved directly. A used version is deleted softly, so the charges priced by it stay explainable.
- **The other changes recalculate nothing by themselves.** The screen where they are made shows "Минулі нарахування не
  змінено. За потреби — Нарахування → Перерахунок." / "Past charges have not changed. If needed — Charges →
  Recalculation."
- **An inactive charge type is left out of a tariff version cause:** it charges nothing, so a rate edit of a tariff of
  an inactive type is saved without recalculating; after the type is made active again, the board runs a manual
  recalculation.
- **A chosen plot pulls its whole run in.** A run is recalculated as a whole, so a manual recalculation of one plot that
  has a charge of a run includes that run with all its plots.
- **Approvals have no recalculation step.** Approving a member's pending reading that falls inside a charged interval
  stays refused (409 `READING_IN_USE`); the board enters the reading on the meter card instead, which opens the
  recalculation step.
- **Data without a dated history is applied to the past as it is today:** the plot area, tariff scopes and exclusions,
  the active and archived flags, the losses mode and the fixed losses percentage. Archived plots, common land and
  inactive charge types are left out. **Known limitation:** after the losses mode was switched from `PROPORTIONAL` to
  `FIXED_PERCENT`, a recalculation of electricity over a period that also has proportional losses runs adds
  fixed-percentage losses to the past intervals while the runs stay (or are booked again): the losses would be charged
  twice. Switched the other way, it removes the fixed losses of the past intervals. The board must not recalculate
  electricity over such a period without checking the preview (choose the months and charge types so that it is left
  out).

### 5.4 Adjustments

- An **adjustment** is a charge of kind `ADJUSTMENT`, source `RECALCULATION`: positive or negative, never `0.00`. It
  points at the `REGULAR` charge it corrects (never a reversal, an adjustment, an opening balance or a reversed charge),
  keeps that charge's plot, charge type, tariff or meter, **service period** and **debt date** (`accrued_on`, so the
  debt age stays honest), is booked in the earliest open month, and carries the reason and its recalculation. Its parts
  are those of the expected charge (the tariff versions and, for an interval, its two points), so those versions and
  readings count as used (6.5).
- One adjustment per original charge and recalculation. An adjustment is never reversed by hand (409
  `ADJUSTMENT_NOT_REVERSIBLE`); a later recalculation adjusts the same original charge again.
- **Debt.** The debt per charge type includes adjustments with their sign (section 2). The oldest unpaid charge of a
  plot (section 9) is the oldest debt date among its charges that are neither a reversal nor an adjustment, were not
  reversed, and whose effective amount is above `0.00`: an adjustment carries the debt age of its charge and never
  counts on its own.
- The plot account shows it as "Коригування за <місяць оригіналу>: −5,00 ₴ — <причина>" / "Adjustment for <original
  month>: −5,00 ₴ — <reason>", with links to the original charge and to the recalculation (the link to the
  recalculation only for those who may view charges). A member sees the adjustments of their own plots.

### 5.5 Keys of the new charges

- A new charge of a recalculation gets the 011 source key when it is free; when a charge already has it (the reversed
  original), it gets the **revision key** `base#r{recalculationId}`, so the 011 uniqueness of keys holds. An identity
  ignores the suffix. An adjustment has the key `ADJUSTMENT:{recalculationId}:{originalChargeId}`.
- **How the 011 paths treat such keys** (the month run, "Повторити" and the charging of readings, `ChargeKeys`): a key
  counts as charged while a charge with it, or with one of its `#r…` keys, stands or was reversed by hand, so nothing
  is booked twice and a hand reversal is never undone. A key whose every charge a recalculation set aside (reversed in
  an open month, or adjusted to `0.00` in a closed month, because the case cannot be charged now and is queued) does
  not count: once the case can be charged, 011 charges it again, once, under `base#r{recalculationId}`, in its own month
  if open, else in the earliest open month with its own service period.
- **The interval check.** The key of an interval also counts as charged while a charge of the same interval (the same
  meter, source and point dates) stands under another key. A reading corrected by a recalculation that adjusted the
  interval, or left its amount unchanged, keeps the charge under the old reading's key while the corrected reading has
  a new one; a reading event delivered again for it books no duplicate. A change booked with a recalculation publishes
  no reading event of its own: the recalculation charges the intervals itself.

### 5.6 The "not charged" queue

- An open item of the scope that the recalculation now charges is resolved; a case found that cannot be charged (for
  example no tariff for some days) is opened or refreshed with its reason as in 3.3, and a booked charge of that case is
  treated as one that should no longer exist; an item whose case is gone becomes obsolete.
- A plan without lines changes no queue item (the month run, "Повторити" and the charging of readings keep them up to
  date), except for a reading change saved alone: no reading event follows it, so it applies its queue outcomes.

### 5.7 Preview, booking and records

- **The preview books nothing.** It lists the lines grouped by plot and month: charge type, booked amount, adjustments
  so far, expected amount, difference, action ("Сторно і нове нарахування", "Коригування", "Нове нарахування",
  "Сторно") with the month it goes to, and why the amount changed (tariff version, reading, area, water connection,
  tariff plots, meter tree, run, not charged); the totals per charge type and overall, the number of plots and the
  scope. A cause-driven preview applies the change and rolls it back.
- **"Змін немає" / "No changes".** A preview without lines offers no booking. Running the same recalculation again
  books nothing: a recalculation is idempotent.
- **Booking** needs a reason (1 to 500 characters), stored on the recalculation and on every reversal and adjustment it
  books. The whole plan is booked in one transaction, or nothing. A booking whose plan no longer matches the preview's
  digest (a reading, a run or another recalculation in between) is refused: "Нарахування змінилися після перегляду.
  Перегляньте знову перед проведенням." (409 `PREVIEW_OUTDATED`).
- **One at a time.** Every path that books or reverses charges of a cooperative (the month run, "Повторити", the
  charging of readings, losses and common consumers runs and their reversals, a reversal by hand, a recalculation and
  its preview) first takes the cooperative's charging lock, so a recalculation reads and books with no other charging in
  between, and a second one waits until the first finishes. A recalculation runs synchronously; the manual range is
  capped at 24 months.
- **Record.** Every booked recalculation is kept: cause (manual, tariff version or reading), scope, reason, author,
  time, total, and its lines (the original charge, booked, adjustments so far, expected, difference, action, the
  charges it created). It is never undone as a whole: a mistake is corrected by another recalculation after the data is
  fixed.
- **Permissions.** Previewing and booking need `RECALCULATION_RUN` (Chairman); a used tariff version also needs
  `TARIFFS_MANAGE`, a used reading `READINGS_ENTER`. Reading the recalculations needs `CHARGES_VIEW`. The demo
  electrician, who may enter readings but not recalculate, sees "Показ уже нараховано — зверніться до голови" and the
  reading stays unchanged.
- **Out of scope:** notifying members about corrections (phase 8), recalculating opening balances, reopening closed
  months, a dated history of plot area or tariff scopes.

### 5.8 Worked examples

Each row is a unit test of the planner (`RecalculationPlannerTests`); the booking tests repeat the key ones end to end.
Membership 50.0000 UAH per sotka per year unless stated, plot 600 m² (25.00 a month), electricity 4.3200 UAH per kWh;
September closed and October open unless stated.

| Case | Input | Expected plan |
|---|---|---|
| Wrong rate corrected | version 60.0000 from 01.09 should have been 50.0000; Sept 30.00 and Oct 30.00 booked | Sept: adjustment −5.00 in Oct; Oct: reversal −30.00 and new 25.00; total after booking 50.00 |
| Back-dated version, open month only | new version 60.0000 from 01.10 entered on 20.10 | Oct: reversal −25.00 and new 30.00; Sept unchanged |
| Back-dated version into a closed month | new version 60.0000 from 16.09 (30-day September) | Sept: expected 12.50 + 15.00 = 27.50, adjustment +2.50 in Oct; Oct: reversal −25.00 and new 30.00 |
| Version deleted, the tariff no longer applies in October | the only October version deleted, the tariff now starts in November | Oct: reversal −25.00; Sept unchanged |
| Reading corrected, both intervals open | 05.10: 8 205 → 8 215; intervals 8 140 → 8 205 and 8 205 → 8 270 (65 kWh, 280.80 each), October and November open | first: reversal and new 75 kWh = 324.00; second: reversal and new 55 kWh = 237.60 |
| Reading corrected, first interval in a closed month | readings 03.08 8 140, 05.09 8 205 → 8 215, 05.10 8 270; the first interval booked in closed September | first: adjustment +43.20 in the earliest open month; second: reversal −280.80 and new 237.60 |
| Reading deleted, intervals merge | readings 03.09 8 140, 05.10 8 205, 05.11 8 270, both intervals charged in open months; 05.10 deleted | both interval charges reversed; one new charge 03.09 → 05.11, 130 kWh = 561.60; total unchanged |
| Reading deleted, one interval in a closed month | readings 03.08 8 140, 05.09 8 205, 05.10 8 270; the first interval booked in closed September; 05.09 deleted | first: adjustment −280.80; second: reversal −280.80; new 561.60 in the earliest open month |
| Back-dated version charges a queued interval | interval 20.09 – 05.10 queued "Немає тарифу на всі дні"; an electricity version from 01.09 added | a new charge for the interval; the queue item resolved |
| Fixed-percentage losses follow the interval | 5 %, an interval corrected from 65 to 75 kWh in an open month | consumption: reversal and new 324.00; losses: reversal of 14.04 and new 3.75 kWh = 16.20 |
| Proportional losses run, open month | a reading of quarter A's meter corrected after the October run | the run reversed as a whole and booked again with the new numbers |
| Proportional losses run, closed month | the same, the run booked in closed September | each plot of the run gets an adjustment for the difference of its share |
| Manual: area corrected | plot area 600 → 700 m² corrected in November, recalculated for September–October | Sept: adjustment +4.17 (29.17 − 25.00); Oct: reversal −25.00 and new 29.17 |
| Already adjusted charge corrected again | Sept 30.00 adjusted by −5.00 earlier, the rate now 40.0000 | effective 25.00, expected 20.00 → adjustment −5.00 |
| Run twice | the same recalculation previewed again after booking | "Змін немає", nothing to book |
| Stale preview | a reading entered between the preview and the booking | 409 `PREVIEW_OUTDATED`, nothing booked |
| Correction that changes nothing | the note of a used version edited | saved, no recalculation |
| Electrician corrects a charged reading | without `RECALCULATION_RUN` | the reading endpoint answers 409 `READING_IN_USE` and `/recalculations` 403; the round shows "Показ уже нараховано — зверніться до голови" |

The requirement (`docs/features/012-retroactive-recalculation.md` §3) states the two "first interval in a closed
month" rows with the readings 03.09, 05.10 and 05.11; with those dates the first interval closes in October, so the
rows above (and the tests) use the readings one month earlier, 03.08, 05.09 and 05.10. The amounts are unchanged.

## 6. Electricity

```
consumption charge   = interval consumption × kWh rate                       (per approved reading, 6.5)
losses of a node     = node consumption − Σ children's consumption            (over a run's days, 6.6)
plot share of losses = node losses × plot consumption / Σ plot consumption   (PROPORTIONAL mode, by runs, 6.6)
                     = interval consumption × fixed %                         (FIXED_PERCENT mode, per reading, 6.5)
losses charge        = share of losses × kWh rate                             (a charge of its own, source LOSSES)
```

- The losses mode is a cooperative setting (`lossesMode`, `lossesPercent`; feature 011): `FIXED_PERCENT` (the default,
  0 % until the chairman sets it) adds a losses charge to every electricity interval and the node meters are for
  information only; `PROPORTIONAL` charges losses only by the runs of 6.6.
- A reading submitted by a member is used only after an admin approves it (status `PENDING` never counts).
- Electricity is charged per reading interval (6.5), not per billing month, so no month-end value is ever needed. A plot
  meter without a reading for more than 60 days is listed by the closing check list (3.1) and the "missing readings"
  filter of the meter register (feature 010); an interval that cannot be priced is a "not charged" item (3.3); a node or
  common consumer whose value is unknown at the end of a run is skipped with a warning (6.6, 6.7).
- A reading is a value on a date, not a value for a period: the start and the end of a billing period are dates, and
  `value(date)` is defined below (6.4). A reading is not forced to fall on the first or the last day of a month.
- Reading values (`MeterValue`) and consumption are quantities of a resource, not money: at most 3 decimals, stored as
  `numeric(14,3)`, written as strings in JSON (`"8205.000"`). Consumption is calculated from exact values (a fraction
  for an interpolated value); the only rounding is `HALF_UP` to 3 decimals for display (`Quantity.toMeterValue`, ADR 0014).
  Money is calculated later from the exact quantity, never from a rounded display value, and is rounded once by `Money`.

### 6.1 Plots without a meter

- Not every plot has an electricity meter (not connected, or common land). A plot has zero or one active meter at a time.
- A plot without an active meter gets no electricity charge, is not in the "missing readings" list, is not shown to the
  electrician for readings and takes no share of transformer losses.
- A meter can be installed on a plot later; its installation date and initial reading start the plot's metering.
- There is no fixed electricity fee for a plot without a meter: no meter, no electricity charge (confirmed by the
  chairman).

### 6.2 Meter replacement

- Each meter is its own record: serial number, plot, installed on, removed on, initial reading. Readings belong to a
  meter, not to a plot, and are never edited in place (corrections follow section 5).
- Within one meter a reading lower than the previous one is rejected. A lower value is valid only as the initial
  reading of a new meter.
- A replacement is entered as one operation: date, final reading of the removed meter, serial number and initial reading
  of the new meter. Both meters keep their history.
- Consumption of a period that contains a replacement is the sum over the meters active in the period:

```
consumption = (final reading of old meter − old meter reading at period start)
            + (new meter reading at period end − initial reading of new meter)
```

- Example: old meter 8 140 at period start, removed at 8 205; new meter installed at 3, reads 69 at period end →
  (8 205 − 8 140) + (69 − 3) = 65 + 66 = 131 kWh.
- A counter that wraps around (for example 99 999 → 00 012) is entered as a replacement of the meter by itself
  (same serial, final 99 999, initial 0), so the rule above still applies.

### 6.3 Meter types and the meter tree

Feedback from the first cooperative's chairman (17.10.2026), answers confirmed 18.10.2026.

- A meter measures one resource: `ELECTRICITY` (kWh) now, `WATER` (m³) later. The meters module is built for both from
  the start; water charges need their own tariff (`PER_M3`) and are added when the cooperative installs water meters.
- Electricity meters form a tree:

```
substation (transformer) meter
├── quarter meter A ── plot meters of the plots it feeds
├── quarter meter B ── plot meters …
└── common consumers: well pumps, street lighting, office
```

- The chairman configures the tree in the cooperative settings: how many substations, which quarter meters and common
  consumers exist, and which plots each one feeds. Nothing about the tree is hard-coded.
- Each meter except a plot meter has a list of the plots (or child meters) it feeds; the board keeps that list. Readings
  of substation, quarter and common-consumer meters are entered by the board or the electrician, on the same schedule
  as plot readings.
- Losses are computed per node: `node losses = node consumption over the days − Σ consumption of its children`, and are
  shared among the plots under that node (mode as in section 6; the runs of the `PROPORTIONAL` mode in 6.6). With no
  quarter meters the tree is one level and section 6 applies unchanged.
- The board report per substation, quarter meter and common consumer shows: readings (start, end, consumption), the
  cost per kWh, and the amount; plus the number of plots and members it feeds, the sum of their plot readings and the
  losses in kWh and %.
- Common consumers (well pumps, lighting): their consumption is a cooperative expense charged to plots as a charge type
  of its own, "Спільні споживачі" / "Common consumers" (seeded in every cooperative, renamable), with its own line and
  debt on the plot account. How each consumer is split is chosen by the chairman (default from the cooperative
  setting): among the plots connected to it (a well pump, by plot-days), equally per plot, only among plots marked as
  using the service, or not charged separately (covered by the membership fee). Charged by runs, 6.7.

### 6.4 Readings, intervals and range consumption

Feature 010 (`ConsumptionCalculator`, pure and tested without a database).

- **A reading is a value on a date.** A meter has at most one approved reading per date. The points of a meter's history
  are its initial value on the installation date, its approved readings and its final value on the removal date; a
  reading on the installation or removal date takes the place of that boundary point. Values never decrease along the
  dates of one meter (a lower value is refused, 409 `READING_OUT_OF_ORDER`); a lower value is valid only as the initial
  value of a new meter (6.2).
- **Statuses.** `APPROVED` readings count. `PENDING` (a member's submission waiting for the board) is never used.
  `REJECTED` carries the reason the member sees and is never used. `SUPERSEDED` is a reading replaced by another entry
  for the same date or by a correction; it keeps its place in the history and is never used. A correction is a new
  reading that supersedes the old one; nothing is overwritten.
- **Interval consumption** is the difference between a point and the previous point of the same meter, counted from the
  installation (initial value) and up to the removal (final value). It is what the reading history shows per row.
- **The value at a date** `valueAt(d)` is, in this order: the approved reading on `d` (real); else, when `d` lies between
  two points `v0` on a date `a` and `v1` on a date `b`, the exact linear interpolation by days
  `(v0 · N + (v1 − v0) · k) / N` with `N = b − a` and `k = d − a`, marked **calculated**; else unknown (no point after the
  date, or none before it), with the date of the last reading so the board knows what to ask for.
- **Range consumption** of a meter for `[from, to]` is `valueAt(to) − valueAt(from)`, clipped to the days the meter worked
  (installation to removal, both included). The consumption of a plot is the sum over the plot's chain of meters (a
  replacement, a wrapped-around counter; 6.2), each clipped to its own working period. It is calculated when any value
  used was interpolated, and unknown when any part is unknown. This is what the meter screens of feature 010 show (the
  consumption of a meter, the tree for a range).
- **The days of a run (feature 011).** A losses run (6.6) and a common consumers run (6.7) charge the days `from` to `to`,
  both included, with end-of-day values: a value on a date is the meter's value at the end of that day, so the
  consumption of the days `a..b` is `valueAt(b) − valueAt(a − 1)` (the same clipping and chains as above). This matches
  the days rule of an interval (6.5: a reading on 4 October after one on 22 September pays for 23 September - 4
  October), and two runs back to back neither overlap nor leave a gap: 1–31 October reads the values of 30 September
  and 31 October, 1–30 November those of 31 October and 30 November. Example: readings 1 000.000 on 30 September and
  1 310.000 on 31 October → the run 1–31 October counts 310.000 kWh (31 days), both values real.
- **Example (interpolation).** Readings 1 000.000 on 1 March and 1 310.000 on 1 April (N = 31 days). The value on
  16 March (k = 15) is (1 000 · 31 + 310 · 15) / 31 = 1 150.000 (calculated). With a reading of 1 480.000 on
  1 May, the range 16 March - 16 April is 1 310 + (1 480 − 1 310) · 15 / 30 − 1 150 = 1 395 − 1 150 = 245.000 kWh, calculated.
- **Tree slices.** For a node of the tree (6.3) over `[from, to]` the range is split by the node's parent periods: one slice
  per period that overlaps the range, from `max(from, valid_from)` to `min(to, valid_to + 1 day)`. Neighbouring slices
  share their boundary day, so the slices add up to the whole range.
- **Unusual consumption** (a warning only, never a refusal): a new interval is flagged when its consumption per day is
  more than three times the average of the meter's earlier intervals that ended within the previous 180 days; no warning
  without such an interval or when that average is zero.
- **Plot meters (`PLOT` kind).** A meter of kind `PLOT` belongs to a plot of kind `PLOT` (never common land) and is the only
  kind that may measure water; at most one active meter per plot and resource. The other kinds (`SUBSTATION`, `QUARTER`,
  `COMMON_CONSUMER`) have a name instead of a plot and measure electricity only.

### 6.5 Charges by readings (feature 011)

- Every approved reading of a plot meter charges one interval: from the previous point of the same meter (the initial
  value after an installation, the previous approved reading) to this reading; a removal or replacement charges the
  interval up to the final value. The initial value alone charges nothing; a pending reading charges nothing until it
  is approved.
- **Days rule.** The days of an interval run from the day after the previous point to the day of the reading, both
  included (a reading on 4 October after one on 22 September covers 23 September - 4 October, 12 days).
- **Amount.** The interval's consumption is spread evenly over its `N` days: each per-kWh (per-m3 for water) version of
  the plot's tariff gets `consumption x d / N` for the `d` days it is valid, times its rate; the parts are added exactly
  and the sum is rounded once (7 kWh at 4.3250 and 1 kWh at 4.3350 is 34.61, not 34.62). The debt counts from the
  reading date; the charge is booked in the month of the reading or the earliest open month after it.
- **Fixed-percentage losses** (`FIXED_PERCENT`, a percentage above zero): the same interval also gets a losses charge
  of `percent` of each part at the same rate, rounded once (65 kWh at 5 % is 3.25 kWh = 14.04).
- **Not charged.** No per-kWh tariff for every day of the interval, the plot outside the scope of every electricity
  tariff or excluded, or two tariffs that would both charge the interval: no charge and an item in the "not charged"
  queue, retried by "Повторити" and by the next month run. An inactive charge type (water until it is switched on)
  charges and queues nothing.
- **Replacement.** A replacement gives two charges: the old meter's last interval up to its final value, and later the
  new meter's first interval from its initial value at its first reading (old meter 8 140 on 03.09, replaced on 20.09
  at 8 205, new meter installed at 3 and read 69 on 05.10: 65 kWh = 280.80 and 66 kWh = 285.12 at 4.32).
- **Water** (per m³) works the same way once the "Вода" charge type is active and has a per-m³ tariff, and only for the
  days the plot is connected to water (section 11): the m³ of the days not connected are dropped with their share, and
  an interval without a connected day charges and queues nothing.
- **In use.** A reading is in use while a charge or a run that is not reversed read the meter on a day it reaches (an
  adjustment reads the two points of its interval too): a correction or deletion of a charged reading, or a new reading
  inside a charged interval, is refused on its own; a new reading after a charged interval is accepted; after the
  reversal of the charge (or the run) the reading may change again. Since feature 012 such a change is made together
  with a recalculation (5.3).

### 6.6 Proportional losses runs (feature 011)

With `lossesMode = PROPORTIONAL` the board runs "Розрахунок втрат" for any range of days (a preview first, then
"Провести"):

- **Per node, bottom up** (quarter meters, then the substation), over the days of the run (6.4):
  `node losses = node consumption − Σ consumption of its children` (plot meters, common consumers and child quarter
  meters, each over the days it was under the node: the tree as it was on each day).
- **Shares.** A node's losses are shared among the plots under it (directly or through child nodes) in proportion to
  each plot's consumption under it in the range; a plot with zero consumption gets nothing. Shares are exact.
- **Price.** A plot's kWh from every charged node of the run are added and priced once with the plot's electricity
  tariff over the range (spread evenly over the days, split by days when the rate changes, one rounding): one `LOSSES`
  charge per plot per run (charge type electricity), naming the node. A plot that cannot be priced is listed with its
  reason and gets nothing.
- **Negative losses** (the children used more than the node shows): the node charges nothing; the preview and the run
  show a warning with the numbers ("−50 кВт·год").
- **Unknown values** (no reading after the end of the range for the node or one of its children): that node and the
  nodes above it are skipped with a warning and queued (3.3); the rest of the tree is charged. Interpolated values are
  allowed and marked "розрахунковий".
- **Overlap.** Two booked runs never charge the same node for the same day (409 `LOSSES_RANGE_OVERLAP`); a node skipped
  or negative can be charged later by a run for its days.
- The charges are booked in the month of the range end (or the earliest open month after it) and count their debt from
  the range end. A whole run is reversed with a reason while its month is open: every charge of the run gets a reversal
  and the run shows "Сторновано"; its days are free again.

| Case | Input | Expected |
|---|---|---|
| One quarter | quarter A 1 100 kWh; P1 600, P2 400, lighting 0 | losses 100: P1 60 kWh = 259.20, P2 40 kWh = 172.80 (4.32) |
| Substation | substation 2 300; quarters A 1 100, B 1 050, the well 50; P1 600, P2 400 in A, P3 1 000 in B | quarter A as above; quarter B: P3 50 kWh; substation 100 kWh: P1 30, P2 20, P3 50 kWh |
| Negative | a quarter 950 kWh, its children 1 000 kWh | nothing charged, warning −50 kWh |
| Unknown | quarter B has no reading after the range end | quarter B and the substation skipped with warnings, quarter A charged |

### 6.7 Common consumers runs (feature 011)

The board runs "Спільні споживачі" for any range of days, separately from losses, with a preview and a confirmation:

- Each common consumer's consumption over the days of the run (6.4) is split as chosen for it (6.3):
  - `CONNECTED_PLOTS` (the default; a well pump): among the plots connected to that water source, by **plot-days**
    (`consumption × the plot's connected days in the range / Σ connected days of all those plots`); a plot not
    connected pays nothing for the pump;
  - `EQUAL_PER_PLOT` (street lighting): equally among all active plots that are not common land, with or without a
    meter;
  - `SERVICE_USERS`: equally among the plots marked as using this consumer;
  - `NOT_CHARGED` (covered by the membership fee): listed with its consumption, nothing charged.
- **Price.** The rate of the "Спільні споживачі" tariff (per kWh, created by the chairman), spread over the plot's own
  days of the split and split by days when it changes, rounded once: one charge per plot per consumer, naming the
  consumer. Without such a tariff valid in the range the run shows "Немає тарифу" and nothing can be booked.
- A consumer whose consumption is unknown is skipped with a warning and queued (3.3); the others are charged. The same
  overlap rule per consumer (409 `COMMON_CONSUMER_RANGE_OVERLAP`), booking month, debt date and reversal of a whole run
  as for losses runs (6.6).

| Case | Input | Expected |
|---|---|---|
| Equally per plot | lighting 50 kWh, 4 active plots (one without a meter), 4.3200 | 12.5 kWh = 54.00 each |
| Connected plots | pump 60 kWh in October, P1-P3 connected all month, P4 not | P1-P3 20 kWh = 86.40 each, P4 nothing |
| Plot-days | pump 60 kWh over 01.10-30.10, P3 connected from 16.10 | P1, P2 24 kWh, P3 12 kWh (30 + 30 + 15 plot-days) |
| Service users | lighting 30 kWh, 3 plots marked as users | 10 kWh = 43.20 each, other plots nothing |

## 7. Allocation

Feature 015. Payments and allocations live in the `ledger` module, so that a payment, its allocations and the debt
change in one transaction. The ledger is append-only (3): a payment, an allocation and every correction of them are
new rows.

**Payment.** Money received for one plot: an amount above 0.00, the day it was received (`received_on`, Kyiv time, not in
the future), the source `CASH` or `BANK`, an optional payer (a member) and payer note, and for a bank payment an
optional bank document number. A payment is booked in the billing month of `received_on` as charges are (3.1); a
payment received in a closed month and entered late goes to the earliest open month and keeps its `received_on`, so the
closed month's totals do not change. Common land and archived plots take no new payments.

**Receipt numbers.** A cash payment gets a receipt number from one counter per cooperative, taken inside the saving
transaction: no gaps (a rolled-back payment does not use a number), no duplicates, never reused or changed. A split cash
payment takes one number for all its parts. A reversed cash payment keeps its number and is shown as reversed. A bank
payment has no number.

**The planner (the proposal).** `AllocationPlanner` is a pure calculator. Given an amount and the plot's charges with an
open amount above 0.00, it fills them in this order:

1. `accrued_on`, the oldest first;
2. the cooperative's order of charge types (the order of the charge types page);
3. the service period start;
4. the booking order (then the charge id).

Each charge receives at most its open amount. What is left is the prepayment.

**The treasurer's change.** Before saving, the treasurer may change the amount of any line or add other open charges of
the plot. The lines may not add up to more than the payment (`ALLOCATION_EXCEEDS_PAYMENT`) and no line may exceed the
charge's open amount (`ALLOCATION_EXCEEDS_CHARGE`). What is not allocated is the remainder; the paragraph on prepayment below says where it goes. The server checks the
digest of the preview it showed; if the ledger changed meanwhile, saving is refused (`PREVIEW_OUTDATED`).

**Reallocation.** A saved payment can be reallocated: its allocations are released and new ones written in one
transaction, with the same checks. A prepayment can also be allocated by hand to the plot's open charges.

**Release.** A release is a negative allocation row that points to the allocation it cancels. Allocations are released
automatically, in the transaction that caused it, when the money they cover is no longer owed:

- a charge is reversed (by hand, by reversing a run or by a recalculation): all its allocations are released;
- a negative adjustment makes a charge's effective amount smaller than its allocations: the excess is released, starting
  with the latest allocation;
- a payment is reversed: all its allocations are released.

The released money is the plot's prepayment again. Every booking path that reverses or lowers a charge calls the one
`AllocationReleaser`.

**Reversal of a payment.** A `PAYMENT_REVERSAL` row with the negated amount and a reason (chairman only). A payment is
reversed once; a part of a split payment is reversed on its own; a payment of an archived plot can still be reversed. The
reversal is booked in the payment's own month while that month is open, otherwise in the earliest open month.

**Prepayment and automatic allocation.** The cooperative setting `autoAllocatePrepayment` (default on) replaces the old
idea of allocating "when a period is closed". With it on, a plot's prepayment is allocated by the same order whenever
charges are booked for the plot (a month run, a reading, a run, an opening balance, a recalculation) and whenever money is
released from a charge. These allocations are written by the system and shown on the account as "Allocated from
prepayment". With the setting on, prepayment never waits while the plot has open debts: when a payment is saved, the
treasurer's lines are kept as chosen and the unallocated remainder is placed at once, in the same transaction, by the
standard order (oldest first, then the charge type order) on the charges still open. A reallocation works the same way:
lines left at 0.00 do not create a waiting prepayment while open debts exist. Prepayment remains only when the plot has
no open debt (it then waits for new charges) or when the setting is off. With the setting off nothing is placed
automatically: the prepayment stays until the treasurer allocates it by hand, which is offered whenever the prepayment is
above 0.00 and a charge is open. Switching the setting on places no old prepayment at once.

**One payment over several plots of one payer.** The treasurer chooses a payer; the form lists the payer's current
plots (not common land, not archived). The proposed split is in proportion to the plots' debts, by `Money.allocate`
(1): each part is rounded down to the kopeck and the leftover kopecks go to the largest remainders, ties to the plot
listed first. An amount above the total debt: the excess goes to the first plot as prepayment; plots owing nothing: the
whole amount goes to the first plot. The treasurer may change any part; the parts must add up to the amount
(`SPLIT_TOTAL_MISMATCH`). Each part is a payment of its plot, all parts share one `group_id`, and a cash group has one
receipt number.

**Concurrency and backstops.** Every payment, allocation and release locks the plot's ledger first (a per-plot
transaction lock, in sorted order for several plots), so two writers on one plot never both see the same open amount.
The database backs this up at commit: deferred triggers fail the transaction if the allocations of a payment exceed it,
the allocations of a charge exceed its effective amount, or a reversed payment keeps an allocation; `payment` and
`allocation` are append-only like `charge`.

**Worked examples** (charge type order: membership, electricity, land tax; each row is a unit test of the planner, the
splitter or the release rules):

| # | Situation | Result |
|---|---|---|
| 1 | Debts: membership 600.00 (Jan), electricity 250.00 (Feb). Payment 700.00 | membership 600.00, electricity 100.00; open electricity 150.00; prepayment 0.00 |
| 2 | Same debts, payment 1000.00 | membership 600.00, electricity 250.00; prepayment 150.00 |
| 3 | Membership 300.00 and electricity 200.00 both accrued on 01.03. Payment 400.00 | membership 300.00 (first in the type order), electricity 100.00 |
| 4 | Example 2, then a land tax charge of 120.00 is booked, auto-allocation on | land tax 120.00 from the prepayment; prepayment 30.00 |
| 5 | Example 4 with auto-allocation off | land tax open 120.00; prepayment 150.00 until the treasurer allocates it |
| 6 | Example 1, then the January membership is reversed | release 600.00; prepayment 600.00; auto-allocation moves 150.00 to the open electricity; prepayment 450.00 |
| 7 | Membership 600.00 fully paid; a recalculation adjusts it by −100.00 | release 100.00 of the latest allocation; prepayment 100.00 |
| 8 | Membership 600.00 paid 600.00; an adjustment of +50.00 | open 50.00; debt 50.00; the plot is no longer GREEN |
| 9 | The payment of 700.00 of example 1 is reversed | releases 600.00 and 100.00; debts back to 600.00 and 250.00 |
| 10 | The treasurer allocates a 700.00 payment as electricity 250.00 and membership 400.00 | saved as chosen; the remainder 50.00 goes on membership at once with auto-allocation on (open membership 150.00), or stays as prepayment with it off (open membership 200.00) |
| 11 | The treasurer allocates 900.00 of a 700.00 payment | refused: `ALLOCATION_EXCEEDS_PAYMENT` |
| 12 | A member owns plots 7 (debt 300.00) and 15 (debt 100.00) and pays 200.00 | proposal 150.00 and 50.00 |
| 13 | Three plots with debts 100.00, 100.00, 100.00; payment 100.00 | 33.34, 33.33, 33.33 (the first plot gets the extra kopeck) |
| 14 | Plots 7 (300.00) and 15 (100.00); payment 500.00 | 300.00 and 100.00, plus 100.00 prepayment on plot 7 |
| 15 | A cash payment split over two plots | two payments, one receipt number, the same `group_id` |
| 16 | A cash payment of 31.01 entered on 03.02, January is closed | booked in February with `received_on` 31.01; January's totals unchanged |
| 17 | Two treasurers save cash payments at the same time | receipts 41 and 42, no gap, no duplicate |
| 18 | Opening balance membership 1200.00, payment 500.00 | allocated to the opening charge; open 700.00 |
| 19 | Debts: membership 600.00 (Jan), electricity 250.00 (Feb). Payment 700.00; the treasurer allocates electricity 250.00 only; auto-allocation on | the remainder 450.00 is placed at once on membership; open membership 150.00; prepayment 0.00 |
| 20 | Example 19 with auto-allocation off | electricity 250.00; the remainder 450.00 stays as prepayment; open membership 600.00 |

## 8. Ownership and sale

- Debt belongs to the plot, not to the person.
- When the owner changes, ownership history is kept and the debt stays on the plot.
- A debt certificate is built as of a date and shows totals per charge type.

## 9. Plot status (map color)

Thresholds are configured per cooperative in the settings ("Поріг червоного статусу"): `red_amount` (default
1 000,00 ₴) and `red_days` (default 90). There are no separate yellow thresholds: yellow is any debt that is not red.

```
debt age = today − date of the oldest unpaid charge
RED    if debt > red_amount OR debt age > red_days
YELLOW if debt > 0 (and not RED)
GREEN  if debt = 0 (including when there is a prepayment)
GREY   common land, or a plot without an owner
```

Visibility: A member always sees full details (status, amounts, owners) of the plots they own. Whether a member also sees the status color of other plots is a cooperative setting chosen by the chairman: `OWN_ONLY` (default; other plots show number, street and kind with a neutral color) or `ALL_PLOTS` (the status color of every plot, still without amounts, owners or personal data). Showing every plot's color should follow a general meeting decision; changing the setting is audited. Admins see what their permissions allow. This is enforced by the API, not only hidden in the UI.

The debt is the debt after payments (2): the sum of the open amounts of the plot's charges. The oldest unpaid charge is
the one with the oldest `accrued_on` among the charges that have an open amount above 0.00, so a charge paid in full
no longer counts and a partly paid one counts with its open part. A plot whose debt is 0.00 is GREEN, also when it has a
prepayment. Examples: membership 600.00 (January) and electricity 250.00 (February) with a payment of 700.00 → debt
150.00, oldest unpaid charge February; with a payment of 1000.00 → debt 0.00, GREEN, prepayment 150.00; the 700.00
payment reversed → debt 850.00, oldest unpaid charge January.

After every charge, reversal, recalculation, payment, payment reversal, allocation and release the debt of the plot,
its prepayment and the date of its oldest unpaid charge are rebuilt from the ledger and cached in `plot_status` (the
cache can be rebuilt from the ledger at any time; it equals a rebuild). The colour itself is derived whenever it is
read, from the cached debt and date, the current thresholds and today, so a change of the thresholds and the passing of
days apply at once without rewriting the cache. "Debt age > red_days" means today minus that date is more than
`red_days` days.

## 10. Land tax

- Charged yearly by plot area and the cooperative's rate (tariff version `PER_AREA_SOTKA`, yearly frequency).

## 11. Water connection

Feature 011. A plot has water connections: the water source (a well, that is a common consumer meter of the pump, or
"Водопровід кооперативу", the cooperative's water supply), connected from a date and, once disconnected, until a date
(both days are connected days). The history is kept; a plot has at most one lasting connection and its connections
never overlap. Common land and archived plots are never connected.

- **Water tariffs charge only connected days.** Any water tariff, by meter readings (`PER_M3`) or a fixed fee, applies
  to a plot only on the days it is connected. A plot that is not connected gets no water charge, is in no
  missing-readings list for water and is never a "not charged" item. A connected plot without a water meter can still
  pay a fixed water fee; charging by m³ also needs an active water meter.
- **Split by days.** When a connection starts or ends inside a month, the month run counts a recurring water tariff
  only for the connected days, like a tariff version (4.1): 300.0000 a year per plot billed monthly is 25.00 for a plot
  connected all October and 300 / 12 × 16 / 31 = 12.90 for one connected from 16 October. A reading interval keeps only
  the m³ of its connected days (6.5).
- **The well pump.** A common consumer split `CONNECTED_PLOTS` (6.7) is shared among the plots connected to that well by
  their connected days.
- A connection entered after a reading was charged does not change that charge; a manual recalculation of water does
  (5.3).
- Connections are entered on the plot card, in bulk on the water source's page in the settings and by the optional
  "Вода" / "Water" column of the Plots import sheet (yes: the cooperative's only source; a source name: that well; no or
  empty: unchanged); connecting and disconnecting are audited.

## 12. Reports

Feature 013. Reports introduce no money rule. Every number in a report or in a filled report template comes from a rule
above, through the read facades of the modules that own it (ADR 0001); a report only selects, groups and adds up.

| Report | Where its numbers come from |
|---|---|
| Charges by month | The effective amount of a charge (5.4: the charge plus its reversals and adjustments) grouped by billing month (3.1), per charge type; the paid column is the allocations to the charges of that month (7), as of the report date |
| Plot balances | The balance (2): charged, paid, debt and prepayment per plot and charge type as of a day, and the plot status (9). A past day shows the status worked out with today's thresholds, because only today's thresholds exist |
| Debtors | The red rule of 9 (debt above `red_amount` for more than `red_days` days) over the same balances; nothing is added |
| Electricity by meter tree | Consumption and losses from the meter tree (6.3: losses are a node's consumption minus its children's) and range consumption (6.4); the charged side from the charges by readings, losses runs and common consumer runs (6.5-6.7) at their effective amount (5.4) |
| Water | Water tariffs and connected days only (7 and 11); the m3 of an interval keeps its connected days (6.5) |
| Missing readings | The same missing-readings rule (6.4) and, for water, the connected days (11) |
| Land tax | Expected against charged (4.1, 10). The expected amount is worked out month by month by the same rules the month run charges with (the tariff version valid in each month and `ChargeCalculator`, rounded once per month), so it equals what the month runs would charge; the charged amount is the effective amount (5.4) of the land tax charges of the year |
| Payments | The payments of 7 (receipt or bank number, date, payer, amount, allocated, prepayment, reversed) grouped by source; the payer note only with the member-data permission |
| Corrections | The reversals, adjustments and recalculation records of 5.4; the sources are those of the recalculation of feature 012 (5.3) |

**Values of a report template.** A template is filled with the figures of the reports above and with these totals,
whose meaning feature 013 fixes:

- `totals.charged.<type>`: the effective amount (5.4), without opening balances, of the charges of the charge type
  whose service period starts (`service_from`) inside the period `from..to` (both days included), booked whenever and on
  every plot. It is the land tax report's year rule over any range of days. A type with no such charges is `0.00`.
- `totals.debt`, `totals.plots.count`, `totals.plots.areaSotka` and the plot rows: the balances of every active plot
  (common land excluded) as of `min(to, today)`; there is no balance of a day that has not come.
- `totals.landTax.*`: the land tax report of the year of `to`.

A value that cannot be determined (a reading that does not exist, a requisite that is not entered) is written empty and
reported in the warnings sheet, never as `0`.

**Rounding.** No report rounds. Every total is the sum of the rows it stands for (`Money.plus`), so a total always equals
its rows. `Money` stays the only place that rounds an amount (1). Quantities (kWh, m3) keep three decimals as the meters
module gives them (6.4), and rates and areas in the `DECIMAL` column type (four decimals) are shown as stored, never
rounded.

**Example (land tax, `ExpectedAmountsTests`).** The rate is 35.00 per sotka a year and the plot is 600 m2, that is 6
sotka: each month charges 6 x 35.00 / 12 = 17.50, and the year is 12 x 17.50 = 210.00, which is the land tax report's
expected and charged amount for the plot. If a second version of 40.00 starts on 16 July, the year has two rates: the
35.00 version applies for 196 days and gives 6 x 17.50 + 8.47 = 113.47 (8.47 is 17.50 x 15/31 for 1-15 July), and the
40.00 version for 169 days gives 10.32 + 5 x 20.00 = 110.32 (10.32 is 20.00 x 16/31 for 16-31 July). The expected amount
is 113.47 + 110.32 = 223.79 over 365 days; the report shows the two shares as rows and no rate for the plot
(`SEVERAL_RATES`).

## 13. Invoices and certificates («Рахунки та довідки»)

Feature 016. An invoice is one plot and one billing month; it adds no money rule of its own. Its figures come from the
ledger through `ledger.LedgerReadModel#invoiceLedgers` and the **booking-month rule** (`ledger/internal/MonthMovements`,
pure):

- A charge (of any kind: regular, reversal, adjustment, opening balance) and a payment (or a payment reversal) count in
  their own billing month (3.1).
- An allocation or a release (7) counts in `GREATEST(payment's month, charge's month, openMonthAt(created_at))`, where
  `openMonthAt(t)` is the month after the last month closed at or before `t` (no bound when nothing was closed by then).
  A November payment placed on an October charge counts in November; an October payment placed on a September debt
  counts in October; a placement made after October was closed counts in November at the earliest.
- Only rows created at or before the moment of the invoice (`asOf`) count.

Which balance each number of the invoice is, per charge type:

| Number | Rule |
|---|---|
| Debt at the start of the month | charges booked before the month − allocations counted before it |
| Charged in the month | charges booked in the month (regular charges, adjustments and reversals with their sign) |
| Paid in the month | allocations counted in the month (releases negative) |
| Debt at the end of the month | start + charged − paid |
| Prepayment («Аванс») | payments booked in or before the month (reversals negative) − allocations counted in or before it, never below 0.00 |
| Received after the month («Отримано після») | payments booked after the month, received before the invoice and not reversed by then |
| **«До сплати»** | Σ end debts − Σ received after the month, never below 0.00; the prepayment does not lower it |

A charge type whose four figures are 0.00 and that has no charge in the month is left out; the rows follow the
cooperative's charge type order. The QR code (`docs/domain/nbu-qr.md`) carries «До сплати» and is printed only when it
is above 0.00. Exact `Money` sums, nothing rounded (1).

**A closed month never changes.** A charge or a payment is booked only in an open month, and a placement created after
the month was closed counts in a later month, so every row a closed month counts was created before it was closed;
the ledger is append-only (3). A correction made later shows up in the invoice of the month it is booked in, with the
month it corrects. Re-issuing a closed month's invoice gives the same figures (only «Отримано після» can grow). Before
any later correction, the end debt of a closed month equals the plot's debt (2) as of the month's last day.

The worked examples (plot 9, charge types Membership then Electricity, October 2026, issued 05.11.2026;
`MonthMovementsTests`, `InvoiceCalculatorTests`):

1. Start 0.00; October membership 75.00 and electricity 338.26; no payments → start 0.00, charged 413.26, paid 0.00,
   end 413.26, «До сплати» 413.26, QR amount 413.26.
2. A September electricity debt of 1 135.08; an October payment of 1 500.00 placed on 1 135.08 (September electricity) +
   75.00 (membership) + 289.92 (October electricity) → start 1 135.08, charged 413.26, paid 1 500.00, end 48.34
   (electricity), «До сплати» 48.34.
3. Example 1 and a payment of 413.26 on 03.11 (booked in November) → the end of October is 413.26, «Отримано після
   жовтня» 413.26, «До сплати» 0.00, no QR block.
4. No debt at the end of October and a prepayment of 100.00 → «До сплати» 0.00, «Аванс 100,00 ₴», no QR block.
5. October is closed on 03.11. On 06.11 a recalculation lowers an October charge by 20.00 (an adjustment booked in
   November, 5.4) and releases the 20.00 paid on it. The release is created after the closing, so it counts in
   November: the October invoice issued on 05.11 and re-issued on 07.11 has the same numbers (electricity 0.00 /
   338.26 / 338.26 / 0.00). The November invoice shows the −20.00 adjustment «за жовтень 2026» in its charged column
   and the release −20.00 in its paid column; the 20.00 becomes the November prepayment.

**Versions.** Every issued version is stored (`invoice`, append-only) with a snapshot of every number and name it
prints; the PDF is rebuilt from the snapshot and never changes. An on-request invoice is always a new version; the
number `{YYYY-MM}/{plot number}` is the same for every version.

**Certificates** (feature 016, a later slice): the debt per charge type, the total and the prepayment as of a day are
the plot balances of 12 (2, by debt date), not the booking-month rule.
