---
id: recalculation-card
title: "Recalculation card"
description: "One booked recalculation: the scope, totals by type, the rows was/should be/difference, the created entries and links to the plot account."
kind: screen
routes:
  - /charges/recalculations/:id
permissions:
  - CHARGES_VIEW
section: tariffs-charges
order: 120
shots:
  - name: recalculation-detail
    user: chairman
    path: /charges/recalculations
    open: { css: 'main a[href^="/charges/recalculations/"]:not([href$="/new"])' }
  - name: plot-account-adjustment
    user: chairman
    path: /plots
    open: { css: 'main a[href^="/plots/"]', text: '2' }
    steps:
      - { check: Adjustments only }
    element: { heading: Account }
---

## What the screen is for

Here you see one booked recalculation in detail: what was recalculated, why, who booked it and how every plot changed. It is view-only. A recalculation is never changed or cancelled, and a mistake is corrected by the next [recalculation](help:recalculation-new).

## Who sees it

The chairman and the treasurer see the card (the "{{ui:permission.CHARGES_VIEW.name}}" permission). A member sees only the adjustments of their own plots in "My charges": the reason, but not the recalculation itself.

## Every element

At the top is the link "{{ui:recalculation.detail.back}}". If the recalculation is not found: "{{ui:recalculation.detail.notFound}}".

### General information

Fields: {{ui:recalculation.detail.cause}}, {{ui:recalculation.detail.months}}, {{ui:recalculation.detail.plots}}, {{ui:recalculation.detail.reason}}, {{ui:recalculation.detail.bookedAt}}, {{ui:recalculation.detail.author}}. The block "{{ui:recalculation.detail.scope}}" shows the months and plots.

### The block "{{ui:recalculation.detail.totals}}"

The difference for each charge type and "{{ui:recalculation.detail.total}}".

### The rows of the recalculation

Each plot has its own block with the heading "{{ui:recalculation.detail.plot}}" and the link "{{ui:recalculation.detail.plotAccount}}". Rows are grouped by month. Columns:

| Column | What it shows |
|---|---|
| {{ui:recalculation.detail.columns.type}} | The charge type. |
| {{ui:recalculation.detail.columns.period}} | For which period. |
| {{ui:recalculation.detail.columns.booked}} | How much was charged. |
| {{ui:recalculation.detail.columns.expected}} | How much should be. |
| {{ui:recalculation.detail.columns.difference}} | The difference. |
| {{ui:recalculation.detail.columns.action}} | The action: "{{ui:recalculation.action.REVERSE_AND_RECHARGE}}", "{{ui:recalculation.action.NEW}}", "{{ui:recalculation.action.REVERSE}}" or an adjustment in an open month. |
| {{ui:recalculation.detail.columns.reasons}} | The reasons for the change, for example "{{ui:recalculation.changeReason.TARIFF_VERSION}}", "{{ui:recalculation.changeReason.READING}}", "{{ui:recalculation.changeReason.WATER_CONNECTION}}", "{{ui:recalculation.changeReason.METER_TREE}}". |
| {{ui:recalculation.detail.columns.created}} | The created entries: "{{ui:recalculation.detail.created.original}}", "{{ui:recalculation.detail.created.reversal}}", "{{ui:recalculation.detail.created.newCharge}}", "{{ui:recalculation.detail.created.adjustment}}"; each links to the plot account. |

If there are no rows, it says "{{ui:recalculation.detail.noLines}}".

### What it looks like on the plot account

An adjustment of a closed month stands on the [plot account](help:plot-card) as a separate row, for example "Adjustment for 09.2026: −5.00 ₴", with a reason, the links "{{ui:charges.adjustment.openOriginal}}" and "{{ui:charges.adjustment.openRecalculation}}" and the mark "{{ui:charges.kind.ADJUSTMENT}}". The tick "{{ui:charges.adjustment.only}}" leaves only such rows.

## Worked example

The recalculation of plot 2 in the demo: the fee rate 60 → 50 ₴ per sotka per year.

1. In the log click "{{ui:recalculation.open}}".
2. In the block "{{ui:recalculation.detail.plot}}" find the two months. In closed September: was 30.00, should be 25.00, difference −5.00, the action is an adjustment in October.
3. In open October: a reversal of −30.00 and a new charge of 25.00.
4. Click "{{ui:recalculation.detail.plotAccount}}" to see the "Adjustment" row on the plot account.

## Screenshots

![The recalculation card with rows and links to entries](shot:recalculation-detail)

![The September adjustment on plot 2's account](shot:plot-account-adjustment)

## Related pages

- [Recalculations: the log](help:recalculations)
- [New recalculation](help:recalculation-new)
- [Plot card](help:plot-card)
- [How the system calculates a recalculation](help:how-recalculation)
