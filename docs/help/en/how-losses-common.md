---
id: how-losses-common
title: "Network losses and common consumers"
description: "How the system counts network losses and shares common consumers among plots, with worked examples."
kind: topic
routes: []
permissions: []
section: how-it-calculates
order: 60
shots: []
---

## Losses: two modes, the chairman chooses

- **Fixed percentage**: every electricity charge gets, for example, 5 % kWh on top. 65 kWh → losses 3.25 kWh = 14.04 ₴.
- **Proportional by node**: the board chooses a range and runs the calculation. For every node, bottom-up: losses = node consumption − the sum of its children's consumption. The losses are shared among the plots under the node in proportion to their consumption.

> **Example: a quarter and the substation**
>
> Quarter A: 1,100 kWh, plots P1 = 600 and P2 = 400 below it → losses 100: P1 60 kWh (259.20 ₴), P2 40 (172.80 ₴).
>
> Quarter B: 1,050, plot P3 = 1,000 → losses 50 → P3.
>
> Substation: 2,300; children A 1,100 + B 1,050 + well 50 = 2,200 → losses 100 shared 600 / 400 / 1,000 of 2,000: P1 30, P2 20, P3 50 kWh.

- **Negative losses** (the plots together "used" more than the node shows: a wrong reading, different reading dates, a faulty meter): nothing is charged, the board sees a warning with the numbers.
- **An unknown value** (no reading of the node or a child after the range end): that node and the nodes above it are skipped with a warning; the rest of the tree is charged.
- Two runs cannot cover the same days of the same node.

## Common consumers — a separate line

Well pumps, street lighting, the office. Their consumption is a separate charge type {{ui:settings.commonConsumers.title}} with its own per-kWh tariff and its own board run. The split is chosen per consumer:

| Split | Who pays | Example |
|---|---|---|
| Connected plots | Plots connected to that well, for their connected days | Pump 60 kWh, three connected plots: 20 kWh each = 86.40 ₴; an unconnected plot nothing |
| Equally | Every active plot, with or without a meter | Lighting |
| Service users | The plots listed in the settings | Lighting 30 kWh, 3 plots: 10 each = 43.20 ₴ |
| Not charged | Nobody — covered by the membership fee | Shown in reports |

## On the screen: {{ui:settings.losses.title}} and {{ui:settings.commonConsumers.title}}

Both tabs of {{ui:nav.charges}} work the same way: pick the range {{ui:audit.fields.from}} and {{ui:audit.fields.to}} (not later than today), press {{ui:billing.rangeRun.preview}}, read the table and warnings, then {{ui:billing.run.confirm}} (permission {{ui:permission.CHARGES_RUN.name}}). The booking is written in the month of the {{ui:audit.fields.to}} day, or in the earliest open month after it. The history of past runs is below, with {{ui:billing.runHistory.details}} and {{ui:billing.runHistory.reverse}} (permission {{ui:permission.CHARGES_REVERSE.name}}, a reason is required).

- The losses mode is chosen in the settings ({{ui:settings.losses.title}}): with a fixed percentage there is nothing to run here and the page says so.
- If some nodes were already charged for these days the whole run cannot be booked; {{ui:billing.losses.excludeOverlap}} books the rest.
- A node with an unknown value goes to {{ui:audit.split.NOT_CHARGED}}; when readings arrive, {{ui:billing.notCharged.retry}} charges it alone.
- Common consumers need a tariff of the {{ui:settings.commonConsumers.title}} charge type; without it the preview says {{ui:billing.common.noTariff}} and nothing can be booked. The split of each consumer, its service users and the default are in the settings.
