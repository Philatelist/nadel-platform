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

## 3. Ledger

- `charge`, `payment` and `allocation` are append-only.
- A wrong charge is cancelled by a `REVERSAL` entry that references the original and states a reason.
- Every entry stores its author, timestamp and reason (for manual operations).

## 4. Tariffs

- A tariff belongs to a charge type (membership fee, electricity, land tax, water, or a custom special-purpose fee). A charge type may have several tariffs. A tariff has versions, each with `valid_from` (effective date). A version applies until the day before the next version's `valid_from`, or without end; the end date is derived, never stored. Versions are never edited or deleted: a change of rate is a new version, and two versions of a tariff never start on the same date.
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

## 6. Electricity

```
plot consumption     = reading(end of period) − reading(start of period)
losses               = common meter for the period − Σ plot consumption
plot share of losses = losses × plot consumption / Σ consumption   (PROPORTIONAL mode)
                     = plot consumption × fixed %                    (FIXED_PERCENT mode)
charge               = (consumption + share of losses) × kWh tariff
```

- The mode is configured per cooperative.
- A reading submitted by a member is used only after an admin approves it.
- If a reading is missing, no electricity charge is created for the period and the plot appears in the "missing readings" list when the period is closed.

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
- Losses are computed per node: `node losses = node reading for the period − Σ readings of its children`, and are
  shared among the plots under that node (mode as in section 6). With no quarter meters the tree is one level and
  section 6 applies unchanged.
- The board report per substation, quarter meter and common consumer shows: readings (start, end, consumption), the
  cost per kWh, and the amount; plus the number of plots and members it feeds, the sum of their plot readings and the
  losses in kWh and %.
- Common consumers (well pumps, lighting): their consumption is a cooperative expense charged to plots. How it is split
  is a cooperative setting chosen by the chairman: equally per plot, only among plots marked as using the service, or
  not charged separately (covered by the membership fee).

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

Thresholds are configured per cooperative: `red_amount`, `red_days`, `yellow_amount` (optional), `yellow_days` (optional).

```
debt age = today − date of the oldest unpaid charge
RED    if debt > red_amount OR debt age > red_days
YELLOW if debt > 0 (and not RED)
GREEN  if debt = 0 (including when there is a prepayment)
GREY   common land, or a plot without an owner
```

Visibility: A member always sees full details (status, amounts, owners) of the plots they own. Whether a member also sees the status color of other plots is a cooperative setting chosen by the chairman: `OWN_ONLY` (default; other plots show number, street and kind with a neutral color) or `ALL_PLOTS` (the status color of every plot, still without amounts, owners or personal data). Showing every plot's color should follow a general meeting decision; changing the setting is audited. Admins see what their permissions allow. This is enforced by the API, not only hidden in the UI.

The status is recalculated after every allocation, charge and recalculation and is stored as a cache in `plot_status` (the cache can be rebuilt from the ledger at any time).

## 10. Land tax

- Charged yearly by plot area and the cooperative's rate (tariff version `PER_AREA_SOTKA`, yearly frequency).
