# Money rules

This document is the source of truth for the `tariffs`, `meters`, `ledger`, `payments` and `documents` modules.
Any rule change is made here first.

## 1. Representing amounts

- Currency is the hryvnia (UAH), 2 decimal places.
- Java: `BigDecimal` through the `Money` value object (module `app.nadel.money`). DB: `numeric(14,2)`. JSON: string `"1250.50"`.
- Rounding is `HALF_UP` to kopecks, done only by the `Money` value object (`Money.of`, `Money.ofQuotient`) and applied only when an amount is produced (a charge, or the expected amount of a plot), never in intermediate calculations: those stay exact `BigDecimal`s.
- A tariff rate is not money. It is the `Rate` value object (module `app.nadel.money`): not negative, at most 10 integer digits and at most 4 decimals, held with scale 4 (`numeric(14,4)`, JSON string `"4.3200"`). A rate is never rounded, not even to 4 places: a fifth decimal is rejected at the boundary, never cut.

## 2. Balance

A plot's balance is never stored. It is calculated:

```
debt per charge type = Σ charges of that type − Σ allocations to that type
prepayment           = Σ payments − Σ allocations
```

Until payments exist (phase 3) there are no allocations: the debt per charge type is the sum of the plot's charges of
that type, reversals counting negative, and the plot's debt is the sum over its charge types (feature 011,
`DebtCalculator`). Example: membership 25.00 and land tax 17.50 → debts 25.00 and 17.50, total 42.50; after the
reversal of the 25.00 the membership debt is 0.00 and the total 17.50.

## 3. Ledger

- `charge`, `payment` and `allocation` are append-only. For `charge` and its parts (`charge_part`) this is enforced by
  the database (feature 011, ADR 0006): the application role has no `UPDATE`, `DELETE` or `TRUNCATE`, and a trigger
  refuses them to every role; the only way past it is the owner role with the transaction setting
  `app.ledger_maintenance = 'on'`, used by demo data and test helpers, never by the application.
- **Kinds.** `REGULAR` (an accrual), `REVERSAL` and `OPENING` (an opening balance, 3.2). A wrong charge is cancelled by a
  `REVERSAL`: the same plot, charge type, service period and debt date, the opposite amount, the original named and a
  mandatory reason. A charge is reversed at most once and a reversal is never reversed. A charge booked in a closed
  month is not reversed by hand: its correction is an adjustment in the open month (feature 012, section 5). A whole
  losses or common consumers run is reversed in one step (6.6, 6.7) while its month is open.
- **Sources.** `TARIFF` (the month run, 4.1), `CONSUMPTION` (an interval of a plot meter, 6.5), `LOSSES` (the
  fixed-percentage losses of an interval, 6.5, or a proportional losses run, 6.6), `COMMON_CONSUMER` (a common
  consumers run, 6.7), `OPENING` (3.2), `MANUAL_REVERSAL` (a reversal by hand or of a run).
- Every entry stores its amount, its **service period** (the days it pays for), its **debt date** (`accrued_on`, the day
  the debt counts from for the plot status, section 9), the billing month it is booked in, its author (none for the
  charge of a reading, which follows the approval of the reading), its timestamp, the reason of a manual operation and
  how it was calculated (codes and numbers only: tariff versions, sotkas or kWh, rates, days, readings, the node or
  consumer of a run). Debt dates: the first day of the billing month for a month-run charge (a one-time fee: its due
  date), the reading date for a charge of a reading, the last day of the range for a run, the given date for an opening
  balance.
- One charge per source and case (a source key: a tariff, plot and month; the two points of an interval; a run and a
  plot), so a rerun, a redelivered event or a retry never charges twice, and a charge reversed by hand is not
  re-created by a rerun or a retry (its correction belongs to feature 012). A charge a recalculation reversed because
  the case can no longer be charged (it is queued) is charged again by a rerun or a retry once it can be, once.

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
  days (an item a run covers only partly is narrowed to the days left). An item disappears (`RESOLVED`) when a retry or
  a run charges it, or the charge exists already; it becomes `OBSOLETE` when nothing is left to charge; otherwise it
  stays open with the reason of the latest attempt. A charge of a retry whose month was closed meanwhile is booked in
  the earliest open month with its own service period. Items never block a month from closing.

## 4. Tariffs

- A tariff belongs to a charge type (membership fee, electricity, land tax, water, or a custom special-purpose fee). A charge type may have several tariffs. A tariff has versions, each with `valid_from` (effective date). A version applies until the day before the next version's `valid_from`, or without end; the end date is derived, never stored. A change of rate is normally a new version, and two versions of a tariff never start on the same date. A version can be corrected or deleted until a charge uses it; after that a correction goes through recalculation (feature 012). The only version of a tariff is never deleted.
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

A new tariff version or a corrected meter reading dated in the past:

1. Find all affected charges.
2. If the period is open: reverse the old charge and create a new one.
3. If the period is closed: nothing changes in the closed period; the current open period gets an `ADJUSTMENT` charge for the difference, referencing the original period.
4. Members whose amount changed receive a notification.

This arrives with feature 012. Until then (feature 011) a tariff version or a reading used by a charge that is not
reversed can no longer be corrected or deleted (409 `TARIFF_VERSION_IN_USE`, `READING_IN_USE`; free again after the
reversal of those charges), and a change that does not touch a used version or reading never changes existing charges:
a new back-dated tariff version, a change of a tariff's scope, a water connection entered after a reading was charged.
In an open month the board corrects such a charge by hand: a reversal with a reason, then the case is charged again
(a new reading has a new interval; a month run never re-creates a reversed charge).

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
- **In use.** A reading is in use while a charge or a run that is not reversed read the meter on a day it reaches: a
  correction or deletion of a charged reading, or a new reading inside a charged interval, is refused; a new reading
  after a charged interval is accepted; after the reversal of the charge (or the run) the reading may change again.

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

- A payment is first saved as unallocated.
- The system proposes an allocation: oldest debts first (FIFO by period); within a period, by the cooperative's charge type priority.
- The treasurer can change the proposal before saving.
- Cash received at the cooperative's cash desk is a payment with source `CASH` and a receipt number. Receipt numbers are sequential per cooperative, never reused and never changed; a mistaken cash payment is reversed like any other ledger entry, and its receipt stays in history marked as reversed.
- The remainder of a payment is a prepayment. When a period is closed, prepayments are automatically allocated to new charges (by the same rules) if the cooperative enables it.

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

After every charge and reversal (later also every allocation and recalculation) the debt of the plot and the date of
its oldest unpaid charge are rebuilt from the ledger and cached in `plot_status` (the cache can be rebuilt from the
ledger at any time). Until payments exist, the debt is the sum of the charges and the oldest unpaid charge is the oldest
`accrued_on` among the positive charges that were not reversed. The colour itself is derived whenever it is read, from
the cached debt and date, the current thresholds and today, so a change of the thresholds and the passing of days apply
at once without rewriting the cache. "Debt age > red_days" means today minus that date is more than `red_days` days.

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
- A connection entered after a reading was charged does not change that charge (section 5).
- Connections are entered on the plot card, in bulk on the water source's page in the settings and by the optional
  "Вода" / "Water" column of the Plots import sheet (yes: the cooperative's only source; a source name: that well; no or
  empty: unchanged); connecting and disconnecting are audited.
