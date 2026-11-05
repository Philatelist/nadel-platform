---
id: charges
title: "Charges: months"
description: "The Charges screen: the list of billing months with status and totals by type, and the tabs Not charged, Losses, Common consumers and Recalculations."
kind: screen
routes:
  - /charges
permissions:
  - CHARGES_VIEW
section: tariffs-charges
order: 50
shots:
  - name: charges
    user: chairman
    path: /charges
---

## What the screen is for

This is the main charges screen. It shows the billing months: the newest on top, with the status and the amount for each type. From here you go to a single month, and to the tabs with the "Not charged" queue, network losses, common consumers and recalculations. A charge is an entry in a plot's account: the type, the amount, the month, the days the service covers and how the amount was obtained.

## Who sees it

The chairman and the treasurer see this screen: those who have the "{{ui:permission.CHARGES_VIEW.name}}" permission. The treasurer sees months, accounts and lists but has no action buttons. A member sees only the account of their own plots on the page [My account](help:account), read-only.

## Every element

### Tabs

At the top there is a row of tabs "{{ui:billing.tabsLabel}}":

| Tab | Where it leads |
|---|---|
| {{ui:billing.tabs.months}} | This screen. |
| {{ui:billing.tabs.notCharged}} | The queue of what the system could not charge: [Not charged](help:not-charged). |
| {{ui:billing.tabs.losses}} | The distribution of network losses: [Losses](help:losses). |
| {{ui:billing.tabs.common}} | Pumps, lighting and other common meters: [Common consumers](help:common-consumers). |
| {{ui:billing.tabs.recalculations}} | The log of corrections of past charges: [Recalculations](help:recalculations). |

### The list of months

The list heading is "{{ui:billing.monthsHeading}}". Each month is a card:

- the month name, for example "Жовтень 2026": click it to open the [month page](help:charges-month);
- the mark "{{ui:billing.status.OPEN}}" or "{{ui:billing.status.CLOSED}}";
- the amounts by charge type, and "{{ui:billing.received}}", that is, how much money came in;
- the row "{{ui:billing.total}}".

If nothing is charged in the month yet, it says "{{ui:billing.noTotals}}". If there are no months at all: "{{ui:billing.empty}}".

### Where charges come from

| Source | How it appears |
|---|---|
| Fixed tariffs | The chairman clicks "{{ui:billing.run.action}}" on the month page. Running it again adds only what is missing. |
| Electricity by readings | Every approved reading at once gives a charge for the interval from the previous reading. |
| Network losses | Depending on the setting: a percentage added to every electricity charge, or a separate run for a period. |
| Common consumers | A separate run for a period, a separate line in the account. |
| Opening balance | The plot's debt on the day it started working in the system: by hand on the [plot card](help:plot-card) or with an Excel sheet in the [import](help:imports). |

### Reversing a charge

Charges are never changed or deleted. A mistaken charge is cancelled by a reversal: an entry for the same amount with a minus and a reason. The button "{{ui:charges.reverse.action}}" (the "{{ui:permission.CHARGES_REVERSE.name}}" permission) is in the charge details on the [plot card](help:plot-card). A charge is reversed once, and a reversal entry is not reversed itself.

> [!important]
> In a closed month there is no "{{ui:charges.reverse.action}}" button. The details of such a charge say: "{{ui:charges.detail.closed}}" A charge of a closed month is corrected by a [recalculation](help:recalculation-new): nothing in the closed month changes, and an adjustment for the difference appears in the nearest open month.

## Worked example

1. Open "{{ui:nav.charges}}". On top is the newest month, marked "{{ui:billing.status.OPEN}}".
2. Look at the card of the previous month: it is marked "{{ui:billing.status.CLOSED}}" and has the amounts by type and "{{ui:billing.total}}".
3. Click the name of the open month to see the "{{ui:billing.checklist.title}}" list and the buttons "{{ui:billing.run.action}}" and "{{ui:billing.close.action}}".
4. Click "{{ui:billing.tabs.notCharged}}" to see whether there are items the system could not charge.

In the demo, plot 14 has a mistaken membership charge in the open month: it is reversed with the reason "Помилкове нарахування". Plot 2 has an adjustment of September of −5.00 ₴ in October.

## Screenshots

![The list of billing months](shot:charges)

## Related pages

- [Month page: charge and close](help:charges-month)
- [Not charged](help:not-charged)
- [Losses](help:losses)
- [Common consumers](help:common-consumers)
- [Recalculations](help:recalculations)
- [Plot card](help:plot-card)
- [How the system calculates charges and closing of months](help:how-charges-months)
