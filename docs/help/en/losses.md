---
id: losses
title: "Network losses"
description: "How to distribute the power network losses between plots for a period: the preview by node, warnings, booking and reversing a run."
kind: screen
routes:
  - /charges/losses
permissions:
  - CHARGES_VIEW
section: tariffs-charges
order: 80
shots:
  - name: charges-losses
    user: chairman
    path: /charges/losses
---

## What the screen is for

The substation shows more electricity than the plot meters under it read: the difference is the loss in the wires. Here the chairman distributes the losses of each node between the plots under it for a period and books them as separate charges. This way the losses are paid by those who consumed.

## Who sees it

The chairman and the treasurer see the screen (the "{{ui:permission.CHARGES_VIEW.name}}" permission). Whoever has "{{ui:permission.CHARGES_RUN.name}}" can preview and book; whoever has "{{ui:permission.CHARGES_REVERSE.name}}" can reverse a run. Without the right to book, the screen says: "{{ui:billing.rangeRun.noAction}}"

## Every element

At the top are the heading "{{ui:billing.losses.heading}}" and the explanation "{{ui:billing.losses.description}}".

### The losses mode

The chairman chooses the mode in the [settings](help:settings), the block "{{ui:settings.losses.title}}":

- "{{ui:settings.losses.FIXED_PERCENT}}": a percentage, for example 5 %, is added to every electricity charge; then there is nothing to calculate on this screen, and it says: "{{ui:billing.losses.notProportional}}"; whoever has the settings right sees the link "{{ui:billing.losses.openSettings}}";
- "{{ui:settings.losses.PROPORTIONAL}}": the board chooses a period and runs the calculation here.

### Period and preview

| Element | What it does |
|---|---|
| {{ui:billing.rangeRun.from}} | The first day of the period. |
| {{ui:billing.rangeRun.to}} | The last day. Hint: "{{ui:billing.rangeRun.toHint}}". |
| {{ui:billing.rangeRun.preview}} | Calculates what will be charged, writes nothing. If the dates are wrong: "{{ui:billing.rangeRun.rangeInvalid}}". |
| {{ui:billing.rangeRun.previewAgain}} | Refreshes the preview. |
| {{ui:billing.rangeRun.book}} | Books exactly what you saw. After that: "{{ui:billing.rangeRun.done}}". |
| {{ui:billing.losses.excludeOverlap}} | Appears when some nodes have already been charged for these days. Books the rest. |

Above the table you see "{{ui:billing.rangeRun.plots}}" and the month in which it will be booked: the charge lands in the month of the "To" day or in the earliest open month after it.

### The table "{{ui:billing.losses.nodesCaption}}"

Columns: {{ui:billing.losses.columns.node}}, {{ui:billing.losses.columns.consumption}}, {{ui:billing.losses.columns.children}}, {{ui:billing.losses.columns.losses}}, {{ui:billing.losses.columns.pct}}, {{ui:billing.losses.columns.plots}} and {{ui:billing.losses.columns.amount}}. A value marked "{{ui:billing.losses.calculated}}" is calculated in proportion to the days between neighbouring readings.

The result for each node: "{{ui:billing.losses.outcome.CHARGED}}", "{{ui:billing.losses.outcome.NEGATIVE}}", "{{ui:billing.losses.outcome.UNKNOWN}}", "{{ui:billing.losses.outcome.SKIPPED_ABOVE}}" or "{{ui:billing.losses.outcome.ALREADY_CHARGED}}".

### Warnings

The block "{{ui:billing.losses.warningsLabel}}" may contain:

| Situation | What it means |
|---|---|
| Negative losses | The plots under the node "consumed" more than the node showed: a reading error, different removal dates or a faulty meter. Nothing is charged, the board sees the numbers. |
| Unknown value | The node or its child has no reading after the end of the period. This node and the nodes above it are skipped, the rest of the tree is charged. The node lands in ["Not charged"](help:not-charged); when readings appear, "Retry" charges it separately. |
| Already charged | The losses for these days were already calculated for the node. Two runs cannot cover the same days of one node: "{{ui:problem.LOSSES_RANGE_OVERLAP}}" |
| No plot consumed | There is nobody to charge. |

### Past runs

The block "{{ui:billing.runHistory.title}}" shows the booked runs: the period, the booking month, the author, the number of plots and nodes. Each has "{{ui:billing.runHistory.details}}" (the list of charges and nodes) and the button "{{ui:billing.runHistory.reverse}}". The dialog "{{ui:billing.reverseRun.submit}}" requires a reason (up to 500 characters) and reverses all the charges of the run together. The charges themselves are not changed or deleted. If the run's month is already closed, a reversal is impossible: "{{ui:problem.RUN_IN_CLOSED_MONTH}}".

## Worked example

Quarter A shows 1,100 kWh. Under it are plots P1 = 600 and P2 = 400, 1,000 in all. The losses of 100 kWh are split in proportion to consumption: P1 gets 60 kWh (60 × 4.32 = 259.20 ₴), P2 gets 40 kWh (172.80 ₴).

Quarter B has 1,050 kWh and one plot P3 = 1,000, so the losses of 50 kWh go to P3.

The substation shows 2,300. Its children: quarter A 1,100, quarter B 1,050 and the well 50, 2,200 in all. The substation's losses of 100 kWh are split between all plots under it in proportion 600 / 400 / 1,000 out of 2,000: P1 gets 30, P2 20, P3 50 kWh.

For comparison, with a fixed percentage of 5 %: 65 kWh of a plot give losses of 3.25 kWh, that is, 14.04 ₴.

In the demo a run is booked for quarter A, and quarter B is shown with a warning about an unknown value.

## Screenshots

![The Losses tab with the run history](shot:charges-losses)

## Related pages

- [Charges: months](help:charges)
- [Common consumers](help:common-consumers)
- [Meter tree](help:meter-tree)
- [Cooperative settings](help:settings)
- [Not charged](help:not-charged)
- [How the system calculates losses and common consumers](help:how-losses-common)
