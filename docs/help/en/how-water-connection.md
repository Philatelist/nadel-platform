---
id: how-water-connection
title: "Water and connections"
description: "How water is charged: the well connection, charges by connected days and the pump share."
kind: topic
routes: []
permissions:
  - SETTINGS_MANAGE
section: how-it-calculates
order: 70
shots: []
---

Water works like electricity: **a plot not connected to the well pays nothing for water**.

- There may be several wells: each plot is connected to a particular well, and each well's pump is shared only among its own plots.
- A plot has a history of water connections: the source (a well or "the cooperative's water supply"), from a date, until a date.
- Any water tariff — by water meter (m³) or fixed (per plot per season, say) — is charged only for the connected days. A per-m³ tariff also needs a water meter.
- A connection starting or ending mid-month splits the amount by days: 300 ₴ per year, connected from 16.10 (31 days) → 300 / 12 × 16 / 31 = **12.90 ₴**.
- The well pump's electricity is shared only among the plots connected to it, by plot-days: P1 and P2 the whole month, P3 from 16.10, pump 60 kWh → 30 + 30 + 15 = 75 plot-days → P1 24, P2 24, P3 12 kWh.
- Connections are entered on the plot card, in bulk on the well's page, or with the {{ui:importColumns.plots.water}} column of the import.

## On the screen

A plot's connection is on its card, panel {{ui:importColumns.plots.water}}: {{ui:water.connect}} (source and the first connected day) and {{ui:water.disconnect}} (the last connected day); the history is listed below. Many plots at once are connected or disconnected on the {{ui:water.sourcesTitle}} section of {{ui:nav.settings}}, per source. Without a connection a plot is never queued in {{ui:audit.split.NOT_CHARGED}} for water and pays nothing for it.

> [!important]
> **Two things to know**
>
> - If a well meter has been removed, its lasting connections are no longer offered in the settings: end them on each plot's card.
> - A plot with a lasting water connection cannot be archived or made common land: end the connection on the plot card first.
