---
id: how-tariffs-expected
title: "Tariffs and the expected amount"
description: "How a tariff turns into the expected amount: units, share of the period, tariff versions and worked examples."
kind: topic
routes: []
permissions: []
section: how-it-calculates
order: 20
shots: []
---

A charge type (membership fee, electricity, land tax, water, special-purpose fees) has one or more tariffs. A tariff has **versions**: each applies from its date until the day before the next version. Two versions never start on the same day.

```
units           per plot → 1 · per sotka → area m² / 100
period share    monthly 1 · quarterly 1/3 · yearly 1/12
month amount    rate × units × period share × (days the version applies in the month) / (days in the month)
one-time fee    the whole rate in the month of its due date, 0.00 in other months
```

## Worked examples

| Case | Given | Result |
|---|---|---|
| Membership fee | 50 ₴/sotka/year, plot 600 m² | 50 × 6 / 12 = 25.00 |
| Odd area | 50 ₴/sotka/year, 612 m² | 50 × 6.12 / 12 = 25.50 |
| Rounded at the end | 33.3333 ₴/sotka/year, 700 m² | 19.444425 → 19.44 |
| Per plot, quarterly | 300 ₴ per quarter | 100.00 per month |
| Version change mid-month | 50 ₴ until the 15th, 60 ₴ from the 16th, 600 m², 30 days | 12.50 + 15.00 = 27.50 |
| Special-purpose fee | 1,200 ₴, due 01.05.2026 | 1,200.00 in May, 0.00 otherwise |
| Land tax | 35 ₴/sotka/year, 600 m² | 17.50 per month |

## Which plots

- {{ui:settings.visibility.ALL_PLOTS.label}} minus exclusions (each with a reason), or {{ui:tariffs.scope.selected}}. Never archived plots or common land.
- Per-kWh and per-m³ tariffs only for plots with an active meter of that resource. **No meter, no charge** (confirmed by the cooperative's chairman).

## Correcting a version

A version can be corrected or deleted **while no charge uses it**. After that a correction goes through recalculation (the page [Corrections and recalculation](help:how-recalculation)): charges already issued to people never change silently.
