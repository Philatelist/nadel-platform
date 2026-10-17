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

Visibility: a member sees the status color and number of every plot, but debt amounts, owners and personal data only for plots they own. Admins see what their permissions allow. This is enforced by the API, not only hidden in the UI.

The status is recalculated after every allocation, charge and recalculation and is stored as a cache in `plot_status` (the cache can be rebuilt from the ledger at any time).

## 10. Land tax

- Charged yearly by plot area and the cooperative's rate (tariff version `PER_AREA_SOTKA`, yearly frequency).
