---
id: not-charged
title: "Not charged"
description: "The queue of items the system could not charge: the reason for each, how to remove it and how to retry the charge."
kind: screen
routes:
  - /charges/not-charged
permissions:
  - CHARGES_VIEW
section: tariffs-charges
order: 70
shots:
  - name: charges-not-charged
    user: chairman
    path: /charges/not-charged
---

## What the screen is for

Sometimes the system cannot charge because something is missing: there is no tariff for part of the days, two tariffs apply to a plot, a meter reading is unknown. All such items land here, with a reason. You remove the reason and retry the charge. Then the item disappears from the list.

## Who sees it

The chairman and the treasurer see the list (the "{{ui:permission.CHARGES_VIEW.name}}" permission). The retry button belongs to the one who can run charges (the "{{ui:permission.CHARGES_RUN.name}}" permission), that is, the chairman.

## Every element

At the top are the heading "{{ui:billing.notCharged.heading}}" with the explanation "{{ui:billing.notCharged.description}}" and the charges tabs. If the queue is empty, the screen says "{{ui:billing.notCharged.empty}}".

### The table

| Column | What it shows |
|---|---|
| {{ui:billing.notCharged.columns.plot}} | The plot number, and for losses or common consumers the node, meter or consumer. |
| {{ui:billing.notCharged.columns.type}} | The charge type or the item type: "{{ui:billing.notCharged.kind.TARIFF_MONTH}}", "{{ui:billing.notCharged.kind.INTERVAL}}", "{{ui:billing.notCharged.kind.LOSSES_NODE}}", "{{ui:billing.notCharged.kind.COMMON_CONSUMER}}". |
| {{ui:billing.notCharged.columns.period}} | For which month or days. |
| {{ui:billing.notCharged.columns.reason}} | The reason and, where possible, a link to the fix, for example "{{ui:billing.notCharged.tariffs}}". |
| {{ui:billing.notCharged.columns.actions}} | The button "{{ui:billing.notCharged.retry}}" (chairman only). |

### Reasons and what to do

| Reason | What to do |
|---|---|
| {{ui:billing.notCharged.reason.NO_TARIFF_FOR_ALL_DAYS}} | Add a tariff version that covers these days on the [tariff card](help:tariff-card). |
| {{ui:billing.notCharged.reason.CONFLICTING_TARIFFS}} | Change the plots of one of the two tariffs so that they no longer overlap. |
| {{ui:billing.notCharged.reason.OUTSIDE_SCOPE}} | Add the plot to a tariff or create a tariff that covers it. |
| {{ui:billing.notCharged.reason.EXCLUDED}} | This is a board decision: the plot was excluded on purpose. If the exclusion is a mistake, remove it on the tariff card. |
| {{ui:billing.notCharged.reason.UNKNOWN_NODE_VALUE}} | The node's meter has no reading after the end of the period. Enter a reading on the screen ["Readings"](help:readings-round). |
| {{ui:billing.notCharged.reason.UNKNOWN_CONSUMER_VALUE}} | The same for a common consumer's meter. |

### The button "{{ui:billing.notCharged.retry}}"

Tries to charge one item again. If it works, the item disappears from the list. If not, you see "{{ui:billing.notCharged.stillNotCharged}}" next to the button, and the reason stays. During the attempt the button says "{{ui:billing.notCharged.retrying}}". The next charging of the month also repeats the tariff and reading records.

Items never block closing a month: they only appear in the checklist before closing.

## Worked example

In the demo, plot 25 has a meter installed on 15.12.2025, before the start of the first electricity tariff version (01.01.2026). Its interval cannot be charged: the list has a row with the reason "{{ui:billing.notCharged.reason.NO_TARIFF_FOR_ALL_DAYS}}".

Also, the node "Квартал Б" has an unknown value: plot 20's meter has no reading later than 75 days ago. That row stands in the losses queue.

1. Open "{{ui:billing.tabs.notCharged}}".
2. Look at the reason next to plot 25's row.
3. Add a tariff version valid from 15.12.2025, or leave it as is if those days really are not payable.
4. Click "{{ui:billing.notCharged.retry}}".

## Screenshots

![The "Not charged" queue with reasons and the retry button](shot:charges-not-charged)

## Related pages

- [Charges: months](help:charges)
- [Tariff card](help:tariff-card)
- [Readings: the electrician's round](help:readings-round)
- [How the system calculates charges and closing of months](help:how-charges-months)
