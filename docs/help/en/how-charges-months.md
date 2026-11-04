---
id: how-charges-months
title: "Charges and closing months"
description: "Where charges come from, how electricity is counted from readings, closing months, reversals, debt and plot colours."
kind: topic
routes: []
permissions:
  - CHARGES_VIEW
section: how-it-calculates
order: 50
shots: []
---

A charge is an entry in the plot's account: charge type, amount, month, the days it pays for (service period) and how the amount was obtained (rates, units, kWh, the readings and tariff versions used).

## Where charges come from

| Source | How it arises |
|---|---|
| Recurring tariffs | The board presses {{ui:billing.run.action}}: preview → "Post". A rerun adds only what is missing, never duplicates. |
| Electricity | Every approved reading at once gives a charge for the interval since the previous reading. |
| Network losses | Depending on the chairman's setting: a percentage on every electricity charge, or a board run for a range (the page [Network losses and common consumers](help:how-losses-common)). |
| Common consumers | A separate board run for a range, a separate line in the account (the page [Network losses and common consumers](help:how-losses-common)). |
| Opening balance | A plot's debt on the day the cooperative starts in the system — by hand or with an Excel sheet. |

## Electricity by reading

The days of an interval run from the day after the previous reading to the day of the new one, both included. When the tariff changes inside the interval, the kWh are split by days, each part times its rate, and the total is rounded once.

> **Examples**
>
> Readings 112.4 (22.09) and 205.7 (04.10): 93.3 kWh × 4.32 = **403.06 ₴** for 23.09–04.10 (12 days).
>
> Readings 1,000 (01.10) and 1,300 (31.10), tariff 4.32 until 16.10 and 5.00 from 17.10: 15 days each, 150 × 4.32 + 150 × 5.00 = 648.00 + 750.00 = **1,398.00 ₴**.
>
> A meter replacement gives two charges: 65 kWh = 280.80 and 66 kWh = 285.12.

## Months and closing

- A month is open or closed. Months are closed only in order; a closed month never reopens.
- Before closing the board sees a check list: is the month posted, what was not charged, which meters have not been read for long. None of it blocks closing — a person decides with everything in view.
- A charge for an already closed month (a late reading, say) goes to the earliest open month, marked "for September, charged in October".

## Reversals and the {{ui:tariffs.expected.notCharged}} queue

- Charges are never changed or deleted — the database itself enforces it. A wrong charge is cancelled by a **reversal** (the same amount with a minus and a reason). A whole losses or common-consumer run can be reversed together.
- What the system could not charge goes to the {{ui:tariffs.expected.notCharged}} queue with the reason: no tariff for some days; two tariffs of one type on a plot on the same day; no node readings. Once the cause is fixed, {{ui:billing.notCharged.retry}}.

## Debt

```
open amount of a charge = the charge with its adjustments (reversed: 0.00) − Σ allocated to it
debt per charge type   = Σ open amounts of the charges of that type
plot debt              = Σ debts per charge type
prepayment             = Σ payments (net of reversals) − Σ allocated
```

A payment pays specific charges, so the debt is what is still unpaid. How a payment is allocated and what happens when a charge is reversed is on the page [How the system allocates payments](help:how-payments-allocate).

## On the screen: {{ui:nav.charges}}

The {{ui:nav.charges}} menu item (permission {{ui:permission.CHARGES_VIEW.name}}) has four tabs: Months, Not charged, Losses and Common consumers.

1. Open a month. The page shows what was charged per charge type and the check list ({{ui:billing.checklist.title}}): is the month run done, how many items wait in {{ui:audit.split.NOT_CHARGED}}, which meters have no reading for more than 60 days.
2. {{ui:billing.run.action}} (permission {{ui:permission.CHARGES_RUN.name}}) first shows a **preview**: the number of plots, the new charges per charge type and the items that cannot be charged. {{ui:billing.run.confirm}} books exactly what was shown. If anything changed meanwhile the system asks to preview again.
3. A rerun creates only what is missing: a plot or a tariff added later gets its charge, nothing is duplicated. A month after the current one cannot be charged.

## {{ui:audit.split.NOT_CHARGED}}

One list of everything the system could not charge, with the reason and a link to fix it: no tariff for every day, two tariffs on the same day, a plot outside every tariff, an unknown node or consumer value. {{ui:billing.notCharged.retry}} tries one item again; the next month run retries the tariff and reading items. An item disappears when the retry succeeds. Items never block closing a month.

## Closing a month

{{ui:billing.close.action}} (permission {{ui:permission.PERIODS_CLOSE.name}}) shows the check list once more and asks to confirm. Only the earliest open month can be closed ({{ui:problem.PERIOD_CLOSE_ORDER}} otherwise). A closed month never changes; late entries go to the earliest open month.

## The plot account, reversals and opening balances

On the plot card the {{ui:plot.tabs.account}} panel shows the debt per charge type and the charges, newest first, with a date filter. {{ui:billing.runHistory.details}} opens the calculation of a charge: kWh, rate, readings, days, the losses percentage. A charge booked later than the days it pays for reads "for 13.09–28.09, charged in October 2026".

- **Reverse** (permission {{ui:permission.CHARGES_REVERSE.name}}) asks for a reason and creates an entry for the same amount with a minus. A charge is reversed once; a reversal cannot be reversed; a charge in a closed month is corrected by an adjustment (a later phase), the button is not offered.
- **Enter an opening balance** (permission {{ui:permission.CHARGES_RUN.name}}): charge type, amount, "debt counts from" (not later than today) and an optional reason. Many plots at once: the {{ui:import.entity.openingBalances}} sheet of the Excel import (the page [Excel import](help:imports)). One opening balance per plot and charge type; to change it, reverse it first.
- Reversing a whole losses or common consumers run is done on its page (below).

## Plot colours on the map and in the list

| Colour | When |
|---|---|
| Red | The debt is above the "red from amount" or the oldest debt is more than "red from days" old (strictly greater) |
| Yellow | Any other debt |
| Green | No debt |
| Grey | No owner, or common land |

The thresholds are set in {{ui:nav.settings}} (defaults 1,000.00 ₴ and 90 days; the demo cooperative uses 2,000.00 ₴ and 90 days).

## The treasurer and the member

The treasurer sees months, accounts and the lists but has no buttons. A member sees only the account of their own plots under {{ui:charges.myTitle}} (the page [My account](help:account)), read-only, with the calculation of every charge; other plots' amounts are never shown.
