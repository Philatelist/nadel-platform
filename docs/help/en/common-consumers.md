---
id: common-consumers
title: "Common consumers"
description: "How to distribute the consumption of the well pump, street lighting and other common meters between plots: split methods, preview, booking and reversal."
kind: screen
routes:
  - /charges/common
permissions:
  - CHARGES_VIEW
section: tariffs-charges
order: 90
shots:
  - name: charges-common
    user: chairman
    path: /charges/common
---

## What the screen is for

Well pumps, street lighting and the office consume electricity for everyone. Their consumption is charged with a separate type "{{ui:chargeTypes.defaultNames.COMMON_CONSUMERS}}" with its own tariff per kWh and a separate line in the invoice. Here the chairman previews the distribution for a period and books it.

## Who sees it

The chairman and the treasurer see the screen (the "{{ui:permission.CHARGES_VIEW.name}}" permission). Whoever has "{{ui:permission.CHARGES_RUN.name}}" can preview and book, and whoever has "{{ui:permission.CHARGES_REVERSE.name}}" can reverse a run. Without the right to book, the screen says: "{{ui:billing.rangeRun.noAction}}"

## Every element

The heading is "{{ui:billing.common.heading}}" and the explanation is "{{ui:billing.common.description}}".

### Split methods

The split method is chosen for each consumer in the [settings](help:settings), the block "{{ui:settings.commonConsumers.title}}". A consumer without its own choice follows the cooperative default.

| Method | Who is charged |
|---|---|
| {{ui:billing.common.split.CONNECTED_PLOTS}} | Plots connected to this well, by the days of connection. A plot that is not connected does not pay. |
| {{ui:billing.common.split.EQUAL_PER_PLOT}} | All active plots, with or without a meter. |
| {{ui:billing.common.split.SERVICE_USERS}} | Plots from the list in the consumer's settings (for example, lighting). |
| {{ui:billing.common.split.NOT_CHARGED}} | Nobody: it is included in the membership fee and shown in reports. |

### Period and preview

It works the same way as on the page [Losses](help:losses).

| Element | What it does |
|---|---|
| {{ui:billing.rangeRun.from}} and {{ui:billing.rangeRun.to}} | The period; "To" no later than today. |
| {{ui:billing.rangeRun.preview}} | Shows what will be charged; writes nothing. |
| {{ui:billing.rangeRun.book}} | Books what you saw: "{{ui:billing.rangeRun.done}}". The charge lands in the month of the "To" day or in the earliest open month after it. |
| {{ui:billing.common.excludeOverlap}} | If some consumers have already been charged for these days, books the rest. |

### The table "{{ui:billing.common.consumersCaption}}"

Columns: {{ui:billing.common.columns.consumer}}, {{ui:billing.common.columns.consumption}}, {{ui:billing.common.columns.split}}, {{ui:billing.common.columns.plotDays}}, {{ui:billing.common.columns.plots}} and {{ui:billing.common.columns.amount}}. A consumer's result: "{{ui:billing.common.outcome.CHARGED}}", "{{ui:billing.common.outcome.NOT_CHARGED}}", "{{ui:billing.common.outcome.NO_PLOTS}}", "{{ui:billing.common.outcome.UNKNOWN}}" or "{{ui:billing.common.outcome.ALREADY_CHARGED}}".

### Messages and warnings

| What is shown | What to do |
|---|---|
| {{ui:billing.common.noTariffNotice}} | Create a [tariff](help:tariff-form) of the type "{{ui:chargeTypes.defaultNames.COMMON_CONSUMERS}}" with the basis "{{ui:tariffs.basis.PER_KWH}}". The button "{{ui:billing.common.openTariffs}}" leads to the tariffs. Without a tariff the table says "{{ui:billing.common.noTariff}}", and nothing can be booked. |
| Unknown value | The consumer's meter has no reading after the end of the period. The consumer lands in ["Not charged"](help:not-charged); when readings appear, "Retry" charges it separately. |
| {{ui:problem.COMMON_CONSUMER_RANGE_OVERLAP}} | The consumer has already been charged for these days. Preview again and book the others. |
| No plots | No plot uses the consumer in this period, so there is nobody to charge. |

### Past runs

The block "{{ui:billing.runHistory.title}}" works as on the page [Losses](help:losses): "{{ui:billing.runHistory.details}}" and "{{ui:billing.runHistory.reverse}}" with a required reason. You can reverse while the run's month is not closed.

## Worked example

A well pump consumed 60 kWh over the period. Three plots are connected to the well for the whole period, one more plot is not connected. The split method is "{{ui:billing.common.split.CONNECTED_PLOTS}}":

- each of the three gets 60 / 3 = 20 kWh, that is, 20 × 4.32 = 86.40 ₴;
- the plot that is not connected pays nothing.

If a plot was not connected for part of the month, plot-days are split. Pump 60 kWh, plots P1 and P2 connected for the whole month, and P3 only from 16.10: that is 30 + 30 + 15 = 75 plot-days. The shares: P1 24, P2 24, P3 12 kWh.

Lighting of 30 kWh over three plots from the list: 10 kWh each, that is, 43.20 ₴ each.

In the demo a run is booked: the pump among the plots connected to it, and the lighting equally among all plots, at the tariff of 4.32 ₴ per kWh.

## Screenshots

![The Common consumers tab](shot:charges-common)

## Related pages

- [Charges: months](help:charges)
- [Losses](help:losses)
- [Cooperative settings](help:settings)
- [Plot card: water connection](help:plot-card)
- [How the system calculates losses and common consumers](help:how-losses-common)
- [Water and connection](help:how-water-connection)
