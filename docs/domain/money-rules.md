# Money rules

This document is the source of truth for the `tariffs`, `meters`, `ledger`, `payments` and `documents` modules.
Any rule change is made here first.

## 1. Representing amounts

- Currency is the hryvnia (UAH), 2 decimal places.
- Java: `BigDecimal` through the `Money` value object. DB: `numeric(14,2)`. JSON: string `"1250.50"`.
- Rounding is `HALF_UP` to kopecks, done only by the `Money` value object and applied only when a charge is created (never in intermediate calculations).

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

- A tariff belongs to a charge type. A tariff has versions with `valid_from` (effective date).
- Calculation basis: `PER_PLOT`, `PER_AREA_SOTKA`, `PER_KWH`, `FIXED_ONE_TIME` (one-off special-purpose fee).
- Frequency: monthly, quarterly, yearly, one-off.
- Example: membership fee 50 UAH per sotka per year, plot of 6 sotkas, charged monthly → 50 × 6 / 12 = 25.00 UAH per month.

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
